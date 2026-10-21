"""Regenerate the bundled blank Office package; python-pptx is a build-time tool only."""
import base64
import io
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from pptx import Presentation

root = Path(__file__).resolve().parents[1]
presentation = Presentation()
assert presentation.slide_layouts[6].name == "Blank"
stream = io.BytesIO()
presentation.save(stream)
output = io.BytesIO()
with ZipFile(stream) as source, ZipFile(output, "w", ZIP_DEFLATED) as target:
    for name in source.namelist():
        data = source.read(name)
        if name == "ppt/presentation.xml":
            data = data.replace(b"<p:sldSz", b"<!--CHK_SLIDES--><p:sldSz")
        target.writestr(name, data)
destination = root / "app/src/main/assets/document_templates/presentation.b64"
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(base64.b64encode(output.getvalue()).decode("ascii") + "\n")
