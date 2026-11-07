import unittest
from urllib.error import HTTPError, URLError

from check_catalog_health import Missing, check


class CatalogHealthTest(unittest.TestCase):
    def result_for(self, error):
        def fail():
            raise error
        return check("fixture", fail)

    def test_http_404_does_not_claim_title_was_removed(self):
        result = self.result_for(HTTPError("https://fixture.invalid", 404, "Not found", {}, None))
        self.assertEqual("http_error", result["status"])
        self.assertEqual("404", result["detail"])

    def test_network_and_source_restriction_are_distinct(self):
        self.assertEqual("network_error", self.result_for(URLError("DNS unavailable"))["status"])
        self.assertEqual("restricted", self.result_for(PermissionError())["status"])

    def test_missing_requires_valid_catalog_evidence(self):
        self.assertEqual("missing_from_catalog", self.result_for(Missing("verified release absent"))["status"])
        self.assertEqual("unexpected_response", self.result_for(ValueError("malformed catalog"))["status"])


if __name__ == "__main__":
    unittest.main()
