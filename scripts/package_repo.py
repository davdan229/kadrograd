"""Collect the actual Gradle outputs and verify their repository metadata."""
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
dist = root / 'dist'
dist.mkdir(exist_ok=True)
plugins = json.loads((root / 'build/plugins.json').read_text(encoding='utf-8'))
assert len(plugins) == 1, 'Expected one Kadrograd package'
package = root / 'Kadrograd/build/Kadrograd.cs3'
assert package.is_file(), 'Gradle package missing'
with zipfile.ZipFile(package) as archive:
    assert 'classes.dex' in archive.namelist(), 'Android bytecode missing'
    manifest = json.loads(archive.read('manifest.json'))
    assert manifest['pluginClassName'] == 'ru.kadrograd.KadrogradPlugin'
digest = hashlib.sha256(package.read_bytes()).hexdigest()
expected_url = 'https://raw.githubusercontent.com/davdan229/kadrograd/builds/Kadrograd.cs3'
assert plugins[0]['url'] == expected_url, plugins[0]['url']
for file in (package, root / 'build/plugins.json', root / 'repo.json'):
    shutil.copy2(file, dist / file.name)
(dist / 'SHA256SUMS').write_text(f'{digest}  Kadrograd.cs3\n', encoding='utf-8')
print(f'Kadrograd.cs3: {package.stat().st_size} bytes, sha256 {digest}')
