#!/usr/bin/env python3
"""Print the package, versionCode and versionName of an Android App Bundle.

Used by scripts/play-upload.sh to refuse any bundle that is not
`com.rousecontext` before it reaches the Play API.

An AAB keeps its manifest at `base/manifest/AndroidManifest.xml` in aapt2's
*protobuf* XML format (Resources.proto `XmlNode`), not the binary-XML format an
APK uses, so `aapt2 dump badging` and friends do not apply. This walks the
protobuf wire format directly -- no bundletool, no JDK, no protobuf package --
reading only the fields it needs:

  XmlNode      { 1: XmlElement element }
  XmlElement   { 3: string name, 4: repeated XmlAttribute attribute }
  XmlAttribute { 1: string namespace_uri, 2: string name, 3: string value }

Checked against `protoc --decode_raw` of the real v1.0.13 Google AAB.

Output, one `key=value` per line:  package=, versionCode=, versionName=.
Exits non-zero, with the reason on stderr, if the file is not a zip, has no
base manifest, or the manifest root is not a <manifest> carrying a package and
a numeric versionCode.
"""

import sys
import zipfile

ANDROID_NS = "http://schemas.android.com/apk/res/android"
MANIFEST_PATH = "base/manifest/AndroidManifest.xml"


def fail(msg):
    sys.stderr.write("aab_manifest: %s\n" % msg)
    sys.exit(2)


def varint(buf, pos):
    result = 0
    shift = 0
    while True:
        if pos >= len(buf):
            raise ValueError("truncated varint")
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7
        if shift > 63:
            raise ValueError("varint too long")


def fields(buf):
    """Yield (field_number, wire_type, value) for one message."""
    pos = 0
    while pos < len(buf):
        key, pos = varint(buf, pos)
        num, wire = key >> 3, key & 7
        if wire == 0:
            val, pos = varint(buf, pos)
        elif wire == 1:
            val, pos = buf[pos:pos + 8], pos + 8
        elif wire == 2:
            length, pos = varint(buf, pos)
            if pos + length > len(buf):
                raise ValueError("truncated length-delimited field")
            val, pos = buf[pos:pos + length], pos + length
        elif wire == 5:
            val, pos = buf[pos:pos + 4], pos + 4
        else:
            raise ValueError("unsupported wire type %d" % wire)
        yield num, wire, val


def main():
    if len(sys.argv) != 2:
        fail("usage: aab_manifest.py <bundle.aab>")
    path = sys.argv[1]
    try:
        with zipfile.ZipFile(path) as z:
            try:
                raw = z.read(MANIFEST_PATH)
            except KeyError:
                fail("%s has no %s -- not an app bundle" % (path, MANIFEST_PATH))
    except zipfile.BadZipFile:
        fail("%s is not a zip file" % path)
    except OSError as e:
        fail("cannot read %s: %s" % (path, e))

    try:
        element = None
        for num, wire, val in fields(raw):
            if num == 1 and wire == 2:
                element = val
                break
        if element is None:
            fail("manifest has no root element")

        name = None
        attrs = {}
        for num, wire, val in fields(element):
            if num == 3 and wire == 2:
                name = val.decode("utf-8")
            elif num == 4 and wire == 2:
                ns = aname = value = ""
                for anum, awire, aval in fields(val):
                    if awire != 2:
                        continue
                    if anum == 1:
                        ns = aval.decode("utf-8")
                    elif anum == 2:
                        aname = aval.decode("utf-8")
                    elif anum == 3:
                        value = aval.decode("utf-8")
                attrs[(ns, aname)] = value
    except (ValueError, UnicodeDecodeError) as e:
        fail("malformed protobuf manifest: %s" % e)

    if name != "manifest":
        fail("manifest root element is %r, not 'manifest'" % name)
    package = attrs.get(("", "package"), "")
    code = attrs.get((ANDROID_NS, "versionCode"), "")
    vname = attrs.get((ANDROID_NS, "versionName"), "")
    if not package:
        fail("manifest has no package attribute")
    if not code.isdigit():
        fail("manifest versionCode %r is not numeric" % code)

    print("package=%s" % package)
    print("versionCode=%s" % code)
    print("versionName=%s" % vname)


if __name__ == "__main__":
    main()
