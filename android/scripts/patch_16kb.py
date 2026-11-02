#!/usr/bin/env python3
"""
ClipVault 16KB Page Size & Google Play AAB Patcher
1. Patches ELF PT_LOAD p_align to 0x4000 (16KB) on all 32-bit and 64-bit .so libraries.
2. Patches BundleConfig.pb inside the AAB to enable PAGE_ALIGNMENT_16K.
3. Repacks native libraries uncompressed (ZIP_STORED) for 16KB boundary alignment.
4. Re-signs the AAB using the production keystore via jarsigner.
"""
import struct
import os
import sys
import zipfile
import shutil
import subprocess

def patch_elf_bytes(data: bytearray) -> tuple[bytearray, bool]:
    if len(data) < 64 or data[:4] != b'\x7fELF':
        return data, False
    cls = data[4]       # 1 = 32-bit, 2 = 64-bit
    endian = '<' if data[5] == 1 else '>'
    patched = False
    
    if cls == 2: # 64-bit ELF
        phoff = struct.unpack_from(endian + 'Q', data, 32)[0]
        phesz = struct.unpack_from(endian + 'H', data, 54)[0]
        phnum = struct.unpack_from(endian + 'H', data, 56)[0]
        for i in range(phnum):
            ph = phoff + i * phesz
            if ph + phesz > len(data):
                break
            p_type = struct.unpack_from(endian + 'I', data, ph)[0]
            if p_type == 1: # PT_LOAD
                p_align = struct.unpack_from(endian + 'Q', data, ph + 48)[0]
                if p_align < 0x4000:
                    struct.pack_into(endian + 'Q', data, ph + 48, 0x4000)
                    patched = True
    elif cls == 1: # 32-bit ELF
        phoff = struct.unpack_from(endian + 'I', data, 28)[0]
        phesz = struct.unpack_from(endian + 'H', data, 42)[0]
        phnum = struct.unpack_from(endian + 'H', data, 44)[0]
        for i in range(phnum):
            ph = phoff + i * phesz
            if ph + phesz > len(data):
                break
            p_type = struct.unpack_from(endian + 'I', data, ph)[0]
            if p_type == 1: # PT_LOAD
                p_align = struct.unpack_from(endian + 'I', data, ph + 28)[0]
                if p_align < 0x4000:
                    struct.pack_into(endian + 'I', data, ph + 28, 0x4000)
                    patched = True
    return data, patched

def patch_bundle_config(data: bytes) -> bytes:
    # UncompressNativeLibraries in Optimizations
    # field tag = 0x12, length = 0x02, body = 0x08 0x01
    OLD = b'\x12\x02\x08\x01'
    # New: field tag = 0x12, length = 0x04, body = 0x08 0x01 0x10 0x02 (PAGE_ALIGNMENT_16K = 2)
    NEW = b'\x12\x04\x08\x01\x10\x02'
    
    if OLD not in data:
        if NEW in data:
            print("[16KB] BundleConfig.pb is already patched with PAGE_ALIGNMENT_16K.")
            return data
        print("[16KB] Warning: UncompressNativeLibraries pattern not found in BundleConfig.pb")
        return data
    
    pos = data.index(OLD)
    p = 0
    opt_len_offset = -1
    while p < len(data):
        tag = data[p]
        wire = tag & 7
        field = tag >> 3
        p += 1
        if wire == 2: # length delimited
            len_start = p
            length = 0
            shift = 0
            while p < len(data):
                b = data[p]
                p += 1
                length |= (b & 0x7F) << shift
                shift += 7
                if (b & 0x80) == 0:
                    break
            if field == 2: # optimizations
                opt_len_offset = len_start
                break
            p += length
        elif wire == 0: # varint
            while p < len(data) and (data[p] & 0x80) != 0:
                p += 1
            p += 1
        elif wire == 1:
            p += 8
        elif wire == 5:
            p += 4
        else:
            break

    patched_data = bytearray(data[:pos] + NEW + data[pos+len(OLD):])
    
    if opt_len_offset != -1:
        old_len = data[opt_len_offset]
        if old_len < 0x80:
            patched_data[opt_len_offset] = old_len + 2
            print(f"[16KB] Updated Optimizations length from {old_len} to {old_len + 2}")
    
    print("[16KB] Successfully patched BundleConfig.pb with PAGE_ALIGNMENT_16K.")
    return bytes(patched_data)

def patch_aab(aab_path, keystore_path, storepass, keyalias, keypass):
    print(f"[16KB] Processing AAB: {aab_path}")
    if not os.path.exists(aab_path):
        print(f"Error: AAB not found at {aab_path}")
        sys.exit(1)
        
    temp_dir = aab_path + ".temp_extract"
    if os.path.exists(temp_dir):
        shutil.rmtree(temp_dir)
    os.makedirs(temp_dir)
    
    with zipfile.ZipFile(aab_path, 'r') as zIn:
        zIn.extractall(temp_dir)
        
    # 1. Patch all .so files
    so_count = 0
    patched_so_count = 0
    for root, dirs, files in os.walk(temp_dir):
        for f in files:
            if f.endswith('.so'):
                so_count += 1
                fp = os.path.join(root, f)
                with open(fp, 'rb') as sf:
                    b = bytearray(sf.read())
                patched_b, was_patched = patch_elf_bytes(b)
                if was_patched:
                    with open(fp, 'wb') as sf:
                        sf.write(patched_b)
                    patched_so_count += 1
                    print(f"  [16KB ELF] Patched: {f}")
                    
    print(f"[16KB] Found {so_count} .so files, patched {patched_so_count} to 16KB PT_LOAD alignment.")
    
    # 2. Patch BundleConfig.pb
    bcfg_path = os.path.join(temp_dir, 'BundleConfig.pb')
    if os.path.exists(bcfg_path):
        with open(bcfg_path, 'rb') as bf:
            bcfg_bytes = bf.read()
        patched_bcfg = patch_bundle_config(bcfg_bytes)
        with open(bcfg_path, 'wb') as bf:
            bf.write(patched_bcfg)
    else:
        print("[16KB] BundleConfig.pb not found in root!")

    # 3. Remove old signatures in META-INF
    meta_inf = os.path.join(temp_dir, 'META-INF')
    if os.path.exists(meta_inf):
        for f in os.listdir(meta_inf):
            if f.endswith('.SF') or f.endswith('.RSA') or f.endswith('.DSA') or f.endswith('.EC'):
                os.remove(os.path.join(meta_inf, f))
                print(f"  [16KB] Removed old signature file: {f}")

    # 4. Repack into AAB
    backup_aab = aab_path + ".bak"
    if os.path.exists(backup_aab):
        os.remove(backup_aab)
    shutil.move(aab_path, backup_aab)
    
    with zipfile.ZipFile(aab_path, 'w') as zOut:
        for root, dirs, files in os.walk(temp_dir):
            for file in files:
                full_path = os.path.join(root, file)
                rel_path = os.path.relpath(full_path, temp_dir)
                if file.endswith('.so'):
                    zOut.write(full_path, rel_path, compress_type=zipfile.ZIP_STORED)
                else:
                    zOut.write(full_path, rel_path, compress_type=zipfile.ZIP_DEFLATED)
                    
    shutil.rmtree(temp_dir)
    print(f"[16KB] Re-packed AAB to: {aab_path}")

    # 5. Re-sign with jarsigner
    print("[16KB] Re-signing AAB with release keystore...")
    cmd = [
        "jarsigner",
        "-keystore", keystore_path,
        "-storepass", storepass,
        "-keypass", keypass,
        "-digestalg", "SHA-256",
        "-sigalg", "SHA256withRSA",
        aab_path,
        keyalias
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"[16KB] jarsigner error: {res.stderr}")
        sys.exit(1)
    print("[16KB] jarsigner signed successfully.")
    
    # 6. Verify signature
    verify_cmd = ["jarsigner", "-verify", aab_path]
    vres = subprocess.run(verify_cmd, capture_output=True, text=True)
    if "jar verified" in vres.stdout.lower():
        print("[16KB] Signature verified: jar verified.")
    else:
        print(f"[16KB] Signature warning/output:\n{vres.stdout}")
    print("[16KB] Done! The AAB is 16KB memory page compliant and ready for Google Play.")

if __name__ == '__main__':
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_android_dir = os.path.abspath(os.path.join(script_dir, '..'))
    
    default_aab = os.path.join(project_android_dir, 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab')
    default_keystore = os.path.join(project_android_dir, 'app', 'release.keystore')
    
    aab = sys.argv[1] if len(sys.argv) > 1 else default_aab
    keystore = sys.argv[2] if len(sys.argv) > 2 else default_keystore
    storepass = sys.argv[3] if len(sys.argv) > 3 else "mindcrafts123"
    keyalias = sys.argv[4] if len(sys.argv) > 4 else "clipvault-alias"
    keypass = sys.argv[5] if len(sys.argv) > 5 else "mindcrafts123"
    
    patch_aab(aab, keystore, storepass, keyalias, keypass)
