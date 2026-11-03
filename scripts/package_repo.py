"""Collect the actual Gradle outputs and verify their repository metadata."""
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

BASE_URL = 'https://raw.githubusercontent.com/davdan229/kadrograd/builds/'


def package_repository(root: Path) -> None:
    plugins = json.loads((root / 'build/plugins.json').read_text(encoding='utf-8'))
    assert len(plugins) == 1, 'Expected one Kadrograd package'
    plugin = plugins[0]
    package = root / 'Kadrograd/build/Kadrograd.cs3'
    payload = package.read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    with zipfile.ZipFile(package) as archive:
        assert 'classes.dex' in archive.namelist(), 'Android bytecode missing'
        manifest = json.loads(archive.read('manifest.json'))
        assert manifest['pluginClassName'] == 'ru.kadrograd.KadrogradPlugin'
        assert plugin['version'] == manifest['version'], 'Package version differs from plugin list'
    assert plugin['url'] == BASE_URL + package.name, 'Unexpected Gradle package URL'
    assert plugin['fileHash'] == 'sha256-' + digest, 'Package hash differs from plugin list'
    assert plugin['fileSize'] == len(payload), 'Package size differs from plugin list'

    dist = root / 'dist'
    assets = dist / 'packages'
    assets.mkdir(parents=True, exist_ok=True)
    # Old and new cached lists can coexist; each must still resolve to its own bytes.
    asset = assets / f'Kadrograd-{digest}.cs3'
    asset.write_bytes(payload)
    plugin['url'] = BASE_URL + asset.relative_to(dist).as_posix()
    (dist / 'plugins.json').write_text(json.dumps(plugins, indent=2) + '\n', encoding='utf-8')
    shutil.copy2(root / 'repo.json', dist / 'repo.json')
    (dist / package.name).write_bytes(payload)  # Compatibility with older plugin lists.
    (dist / 'SHA256SUMS').write_text(f'{digest}  {asset.relative_to(dist).as_posix()}\n{digest}  {package.name}\n', encoding='utf-8')
    print(f'{asset.name}: {len(payload)} bytes')


if __name__ == '__main__':
    package_repository(Path(__file__).resolve().parents[1])
