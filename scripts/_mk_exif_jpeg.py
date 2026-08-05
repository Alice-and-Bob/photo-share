#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成一张带 EXIF（Make/Model/Orientation/DateTime）的测试 JPEG，用于验证 MediaStore 写入后 EXIF 是否保留。"""
import struct, io, sys
from PIL import Image


def build_app1():
    def tag(t, typ, cnt, val4):
        return struct.pack('<HHI4s', t, typ, cnt, val4)

    make = b'SONY\x00'
    model = b'ILCE-7M4\x00'
    dt = b'2026:08:05 12:00:00\x00'

    n = 4
    data_start = 8 + 2 + n * 12 + 4
    off_make = data_start
    off_model = off_make + len(make)
    off_dt = off_model + len(model)

    entries = b''
    entries += tag(0x010F, 2, len(make), struct.pack('<I', off_make))     # Make
    entries += tag(0x0110, 2, len(model), struct.pack('<I', off_model))   # Model
    entries += tag(0x0112, 3, 1, struct.pack('<HH', 1, 0))                # Orientation = 1
    entries += tag(0x0132, 2, len(dt), struct.pack('<I', off_dt))         # DateTime

    tiff = b'II*\x00' + struct.pack('<I', 8)
    tiff += struct.pack('<H', n) + entries + struct.pack('<I', 0)
    tiff += make + model + dt

    payload = b'Exif\x00\x00' + tiff
    return b'\xff\xe1' + struct.pack('>H', len(payload) + 2) + payload


def main(out_path):
    buf = io.BytesIO()
    img = Image.new('RGB', (320, 240), (200, 60, 40))
    # 画点内容，避免纯色被某些解码器优化
    for x in range(0, 320, 40):
        for y in range(0, 240, 40):
            if (x // 40 + y // 40) % 2 == 0:
                for dx in range(40):
                    for dy in range(40):
                        img.putpixel((x + dx, y + dy), (40, 90, 200))
    img.save(buf, 'JPEG', quality=92)
    base = buf.getvalue()
    out = base[:2] + build_app1() + base[2:]
    with open(out_path, 'wb') as f:
        f.write(out)
    print(f'wrote {out_path} {len(out)} bytes (EXIF: Make=SONY Model=ILCE-7M4 DateTime=2026:08:05 12:00:00)')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'exif_test.jpg')
