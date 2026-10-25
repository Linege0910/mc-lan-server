import io, os, re, sys, tarfile, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from debinspect import ar_members
from inspect import read_elf_needed

MIRROR = "https://mirrors.nju.edu.cn/termux/apt/termux-main"
WORK = os.path.join(os.environ.get("TEMP", "/tmp"), "jdkclosure")
SYSTEM = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
          "libstdc++.so", "libz.so", "libnativehelper.so", "libc++_shared.so"}


def fetch(url, path):
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    with urllib.request.urlopen(url, timeout=300) as r, open(path, "wb") as f:
        f.write(r.read())
    return path


def control_of(deb):
    mem = ar_members(deb)
    key = [k for k in mem if k.startswith("control.tar")][0]
    tf = tarfile.open(fileobj=io.BytesIO(mem[key]))
    for name in tf.getnames():
        if name.endswith("control"):
            text = tf.extractfile(name).read().decode()
            out = {}
            for line in text.splitlines():
                if line.startswith(" ") or ":" not in line:
                    continue
                k, v = line.split(":", 1)
                out[k.strip()] = v.strip()
            return out
    return {}


def deps_of(control):
    raw = control.get("Depends", "")
    out = []
    for part in raw.split(","):
        name = part.split("|")[0].split("(")[0].strip()
        if name:
            out.append(name)
    return out


def extract_deb(deb, dest):
    mem = ar_members(deb)
    key = [k for k in mem if k.startswith("data.tar")][0]
    tf = tarfile.open(fileobj=io.BytesIO(mem[key]))
    prefix = "data/data/com.termux/files/usr/"
    n = 0
    pending_links = []
    for m in tf.getmembers():
        name = m.name.lstrip("./")
        if not name.startswith("data/data/com.termux/files/usr/"):
            continue
        rel = name[len(prefix):]
        if not rel or "/demo/" in rel or rel.startswith("demo/") or "/sample/" in rel:
            continue
        target = os.path.join(dest, rel)
        if m.isdir():
            os.makedirs(target, exist_ok=True)
        elif m.issym() or m.islnk():
            pending_links.append((target, m.linkname))
            n += 1
        elif m.isfile():
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "wb") as f:
                f.write(tf.extractfile(m).read())
            n += 1
    # 与 App 一致：全部条目读完后再落地符号链接
    for link, target_name in pending_links:
        try:
            if os.path.exists(link) or target_name.startswith("/"):
                continue
            src = os.path.join(os.path.dirname(link), target_name)
            if os.path.exists(src):
                try:
                    os.symlink(target_name, link)
                except OSError:
                    import shutil
                    shutil.copyfile(src, link)
        except OSError:
            pass
    return n


def package_path(name):
    idx = os.path.join(WORK, "Packages")
    with open(idx, encoding="utf-8", errors="replace") as f:
        text = f.read()
    for stanza in text.split("\n\n"):
        m = re.search(r"(?m)^Package: (.+)$", stanza)
        if not m or m.group(1).strip() != name:
            continue
        fm = re.search(r"(?m)^Filename: (.+)$", stanza)
        return fm.group(1).strip() if fm else None
    return None


def main():
    jdk_deb = sys.argv[1]
    home = os.path.join(WORK, "jdk")
    if os.path.isdir(home):
        import shutil
        shutil.rmtree(home)
    os.makedirs(home, exist_ok=True)

    n = extract_deb(jdk_deb, home)
    print("JDK 文件数:", n)
    # 找到真正的 JDK 根（deb 里在 lib/jvm/java-N-openjdk）
    root = home
    for dirpath, dirnames, filenames in os.walk(home):
        if os.path.basename(dirpath) == "bin" and "java" in filenames:
            root = os.path.dirname(dirpath)
            break
    print("JDK 根目录:", os.path.relpath(root, home)); home = root

    done, level, depth = set(), deps_of(control_of(jdk_deb)), 0
    while level and depth < 4:
        nxt = []
        for name in level:
            if name in done:
                continue
            done.add(name)
            p = package_path(name)
            if not p:
                print("  跳过（索引里没有）:", name)
                continue
            deb = fetch(MIRROR + "/" + p, os.path.join(WORK, name + ".deb"))
            extract_deb(deb, home)  # 与 App 一致：依赖解到 JDK 根
            nxt.extend(deps_of(control_of(deb)))
            print("  已装依赖:", name)
        level = [x for x in nxt if x not in done]
        depth += 1

    print("\n== 链接闭包检查 ==")
    libdirs = [os.path.join(home, "lib"), os.path.join(home, "lib", "server")]
    present = set()
    for d in libdirs:
        if os.path.isdir(d):
            present.update(os.listdir(d))

    targets = ["bin/java", "lib/libjli.so", "lib/libjava.so", "lib/libzip.so",
               "lib/libnet.so", "lib/libnio.so", "lib/server/libjvm.so"]
    bad = 0
    for rel in targets:
        p = os.path.join(home, rel)
        if not os.path.isfile(p):
            print("  缺失文件:", rel)
            bad += 1
            continue
        needed, status = read_elf_needed(p)
        missing = [x for x in (needed or []) if x not in SYSTEM and x not in present]
        flag = "OK" if not missing else "缺 " + ",".join(missing)
        if missing:
            bad += 1
        print("  %-24s %s" % (rel, flag))
    print("\n结果:", "全部满足 ✔" if bad == 0 else "仍有 %d 项缺失" % bad)


if __name__ == "__main__":
    main()
