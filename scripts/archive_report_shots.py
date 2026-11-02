"""Copy selected review screenshots into docs as compact JPEGs. Usage: SRC_DIR DEST_DIR NAME=REPORT/VIEW.png ..."""
import pathlib
import sys

from PIL import Image

src, dest = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
dest.mkdir(parents=True, exist_ok=True)
for spec in sys.argv[3:]:
    name, rel = spec.split('=', 1)
    image = Image.open(src / rel).convert('RGB')
    out = dest / (name + '.jpg')
    image.save(out, 'JPEG', quality=86, optimize=True, progressive=True)
    print(out, image.size, out.stat().st_size)
