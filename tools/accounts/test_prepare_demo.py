"""Filesystem-only checks for repeatable demo configuration preparation."""
import contextlib
import importlib.util
import io
from pathlib import Path
import stat
import tempfile
import unittest

SOURCE = Path(__file__).with_name('prepare_demo.py')
SPEC = importlib.util.spec_from_file_location('prepare_demo', SOURCE)
demo = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(demo)


class PrepareDemoTest(unittest.TestCase):
    def setUp(self):
        # Resolve the OS temp root so macOS /var and /tmp aliases are not passed
        # as intentionally rejected symlink ancestors of the test output.
        self.temporary = tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve())
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name) / 'demo settings'

    def test_generation_is_private_and_contains_only_references(self):
        config, directory = demo.prepare(self.directory, 8081, 5432)
        content = config.read_text()
        self.assertIn('spring.profiles.active=local-accounts\n', content)
        self.assertIn('spring.datasource.url=jdbc\\:postgresql\\://127.0.0.1\\:5432/lookahead_accounts\n', content)
        self.assertIn('server.address=127.0.0.1\nserver.port=8081\n', content)
        self.assertIn('LOOKAHEAD_SECRETS_DIRECTORY=' + demo.property_value(str(directory) + '/') + '\n', content)
        self.assertIn('spring.config.import=' + demo.property_value('configtree:' + str(directory) + '/') + '\n', content)
        self.assertIn('app.accounts.catalog-path=' + demo.property_value(demo.API_ROOT / 'src/test/resources/accounts/catalog.json') + '\n', content)
        self.assertEqual(stat.S_IMODE(self.directory.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE(directory.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE(config.stat().st_mode), 0o600)
        values = []
        for name in demo.SECRET_NAMES:
            secret = directory / name
            value = secret.read_text().strip()
            self.assertGreaterEqual(len(value), 43)
            self.assertNotIn(value, content)
            self.assertEqual(stat.S_IMODE(secret.stat().st_mode), 0o600)
            values.append(value)
        self.assertEqual(len(set(values)), 3)

    def test_identical_rerun_preserves_all_bytes_and_prints_no_secret(self):
        config, directory = demo.prepare(self.directory)
        before = {file.name: file.read_bytes() for file in directory.iterdir()}
        config_before = config.read_bytes()
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            self.assertEqual(demo.main(['--directory', str(self.directory)]), 0)
        self.assertEqual(config.read_bytes(), config_before)
        self.assertEqual({file.name: file.read_bytes() for file in directory.iterdir()}, before)
        for value in before.values():
            self.assertNotIn(value.decode().strip(), stdout.getvalue())

    def test_invalid_ports_do_not_create_output(self):
        for value in ('0', '65536', '-1', 'no', '1.2', True):
            for keyword in ('port', 'database_port'):
                with self.subTest(value=value, keyword=keyword), self.assertRaises(ValueError):
                    demo.prepare(self.directory, **{keyword: value})
        self.assertFalse(self.directory.exists())
        demo.prepare(self.directory, 1, 65535)

    def test_changed_configuration_is_rejected_and_preserved(self):
        config, directory = demo.prepare(self.directory)
        before = config.read_bytes()
        secrets_before = {file.name: file.read_bytes() for file in directory.iterdir()}
        with self.assertRaisesRegex(ValueError, 'different settings'):
            demo.prepare(self.directory, port=8082)
        self.assertEqual(config.read_bytes(), before)
        self.assertEqual({file.name: file.read_bytes() for file in directory.iterdir()}, secrets_before)

    def test_empty_existing_secret_is_rejected_without_creating_others(self):
        secret_directory = self.directory / 'secrets'
        secret_directory.mkdir(parents=True)
        (secret_directory / demo.SECRET_NAMES[0]).write_text(' \n')
        with self.assertRaisesRegex(ValueError, 'empty'):
            demo.prepare(self.directory)
        self.assertEqual(len(list(secret_directory.iterdir())), 1)
        self.assertFalse((self.directory / 'application.properties').exists())

    def test_symlink_directory_and_parent_are_rejected(self):
        actual = Path(self.temporary.name) / 'actual'
        actual.mkdir()
        self.directory.symlink_to(actual, target_is_directory=True)
        for output in (self.directory, self.directory / 'nested'):
            with self.subTest(output=output), self.assertRaisesRegex(ValueError, 'symlinks'):
                demo.prepare(output)
        self.assertEqual(list(actual.iterdir()), [])

    def test_symlink_secret_directory_and_files_are_rejected(self):
        actual = Path(self.temporary.name) / 'actual'
        actual.mkdir()
        self.directory.mkdir()
        (self.directory / 'secrets').symlink_to(actual, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'symlinks'):
            demo.prepare(self.directory)
        (self.directory / 'secrets').unlink()
        (self.directory / 'secrets').mkdir()
        target = actual / 'existing'
        target.write_text('test-placeholder-never-generated')
        for path in (self.directory / 'application.properties', self.directory / 'secrets' / demo.SECRET_NAMES[0]):
            path.symlink_to(target)
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, 'symlinks'):
                demo.prepare(self.directory)
            path.unlink()
        self.assertEqual(target.read_text(), 'test-placeholder-never-generated')

    def test_dangling_symlinks_are_rejected(self):
        self.directory.mkdir()
        path = self.directory / 'application.properties'
        path.symlink_to(self.directory / 'missing')
        with self.assertRaisesRegex(ValueError, 'symlinks'):
            demo.prepare(self.directory)


if __name__ == '__main__':
    unittest.main()
