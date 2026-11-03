import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from package_repo import BASE_URL, package_repository


class PackageRepositoryTest(unittest.TestCase):
    def test_cached_indexes_keep_resolving_to_their_own_packages(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / 'Kadrograd/build/Kadrograd.cs3'
            artifact.parent.mkdir(parents=True)
            (root / 'build').mkdir()
            (root / 'repo.json').write_text('{}', encoding='utf-8')
            indexes = []
            for version in (7, 8):
                with zipfile.ZipFile(artifact, 'w') as archive:
                    archive.writestr('classes.dex', b'test fixture')
                    archive.writestr('manifest.json', json.dumps({
                        'version': version, 'pluginClassName': 'ru.kadrograd.KadrogradPlugin',
                    }))
                digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
                (root / 'build/plugins.json').write_text(json.dumps([{
                    'version': version, 'url': BASE_URL + artifact.name,
                    'fileHash': 'sha256-' + digest, 'fileSize': artifact.stat().st_size,
                }]), encoding='utf-8')
                package_repository(root)
                indexes.append(json.loads((root / 'dist/plugins.json').read_text(encoding='utf-8'))[0])
            self.assertNotEqual(indexes[0]['url'], indexes[1]['url'])
            for index in indexes:
                published = root / 'dist' / index['url'].removeprefix(BASE_URL)
                self.assertEqual(index['fileHash'], 'sha256-' + hashlib.sha256(published.read_bytes()).hexdigest())
                with zipfile.ZipFile(published) as archive:
                    self.assertEqual(index['version'], json.loads(archive.read('manifest.json'))['version'])
