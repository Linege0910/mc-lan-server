import struct, sys, zipfile

def read_elf_needed(path):
    data = open(path, 'rb').read()
    if data[:4] != b'\x7fELF':
        return None, 'not elf'
    is64 = data[4] == 2
    if not is64:
        return None, 'not 64-bit'
    e_shoff = struct.unpack_from('<Q', data, 0x28)[0]
    e_shentsize = struct.unpack_from('<H', data, 0x3A)[0]
    e_shnum = struct.unpack_from('<H', data, 0x3C)[0]
    e_shstrndx = struct.unpack_from('<H', data, 0x3E)[0]

    def sh(i):
        off = e_shoff + i * e_shentsize
        name, typ = struct.unpack_from('<II', data, off)
        addr, offset, size = struct.unpack_from('<QQQ', data, off + 0x10)
        link, info = struct.unpack_from('<II', data, off + 0x28)
        entsize = struct.unpack_from('<Q', data, off + 0x38)[0]
        return dict(name=name, type=typ, addr=addr, offset=offset, size=size, link=link, entsize=entsize)

    dyn = None
    dynsym = None
    dynstr = None
    for i in range(e_shnum):
        s = sh(i)
        if s['type'] == 6:   # SHT_DYNAMIC
            dyn = s
        elif s['type'] == 11:  # SHT_DYNSYM
            dynsym = s
    if dyn is None:
        return [], 'no dynamic section'
    # string table referenced by dynamic = section index dyn['link']
    dynstr = sh(dyn['link'])
    strtab = data[dynstr['offset']:dynstr['offset'] + dynstr['size']]
    needed = []
    p = dyn['offset']
    end = p + dyn['size']
    while p < end:
        tag, val = struct.unpack_from('<qQ', data, p)
        if tag == 0:
            break
        if tag == 1:  # DT_NEEDED
            s = strtab[val:strtab.index(b'\0', val)].decode()
            needed.append(s)
        p += 16
    return needed, 'ok'


def read_ar_members(path):
    data = open(path, 'rb').read()
    if data[:8] != b'!<arch>\n':
        return []
    p = 8
    out = []
    while p + 60 <= len(data):
        name = data[p:p+16].decode('latin1').strip()
        size = int(data[p+48:p+58].decode('latin1').strip())
        body_off = p + 60
        out.append((name, body_off, size))
        p = body_off + size + (size % 2)
    return out


if __name__ == '__main__':
    mode = sys.argv[1]
    path = sys.argv[2]
    if mode == 'ar':
        for n, o, s in read_ar_members(path):
            print(n, s)
    elif mode == 'elf':
        needed, status = read_elf_needed(path)
        print(status, needed)
