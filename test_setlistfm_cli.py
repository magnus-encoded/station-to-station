import unittest
import json
from unittest.mock import patch, Mock
from click.testing import CliRunner
import requests

# Import the cli function from your script
import setlistfm_cli

class SetlistFMCliTests(unittest.TestCase):

    def setUp(self):
        """Set up the test runner to capture stderr separately."""
        # FIX 1: Initialize CliRunner with mix_stderr=False
        self.runner = CliRunner(mix_stderr=False)

    @patch('setlistfm_cli.requests.get')
    def test_search_artists_success(self, mock_get):
        """Test the 'search artists' command with a successful API response."""
        # Mock the API response to match the documented structure for search
        mock_response = Mock()
        expected_json = {
            "artists": {
                "artist": [
                    {
                        "mbid": "65f4f0c5-ef9e-490c-aee3-909e7ae6b2ab",
                        "name": "Metallica",
                        "sortName": "Metallica",
                        "url": "https://www.setlist.fm/setlists/metallica-3bd6a4b8.html"
                    }
                ],
                "total": 1,
                "page": 1,
                "itemsPerPage": 20
            }
        }
        mock_response.status_code = 200
        mock_response.json.return_value = expected_json
        mock_get.return_value = mock_response

        # Invoke the CLI command
        result = self.runner.invoke(
            setlistfm_cli.cli,
            ['--api-key', 'fakekey', 'search', 'artists', '--artist-name', 'Metallica']
        )

        # Assertions
        self.assertEqual(result.exit_code, 0)
        self.assertEqual(json.loads(result.output), expected_json)

        # Verify requests.get was called correctly
        mock_get.assert_called_once_with(
            'https://api.setlist.fm/rest/1.0/search/artists',
            headers={'x-api-key': 'fakekey', 'Accept': 'application/json'},
            params={'artistName': 'Metallica'}
        )

    @patch('setlistfm_cli.requests.get')
    def test_get_artist_success(self, mock_get):
        """Test the 'artist' command with a successful API response."""
        # Mock the API response to match the documented Artist object structure
        mock_response = Mock()
        mbid = "b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d"
        expected_json = {
            "mbid": mbid,
            "name": "The Beatles",
            "sortName": "Beatles, The",
            "disambiguation": "The legendary rock band from Liverpool",
            "url": "https://www.setlist.fm/setlists/the-beatles-23d6a88b.html"
        }
        mock_response.json.return_value = expected_json
        mock_response.status_code = 200
        mock_get.return_value = mock_response
        
        # Invoke the CLI command
        result = self.runner.invoke(
            setlistfm_cli.cli,
            ['--api-key', 'fakekey', 'artist', mbid]
        )

        # Assertions
        self.assertEqual(result.exit_code, 0)
        self.assertEqual(json.loads(result.output), expected_json)
        mock_get.assert_called_once_with(
            f'https://api.setlist.fm/rest/1.0/artist/{mbid}',
            headers={'x-api-key': 'fakekey', 'Accept': 'application/json'},
            params={}
        )

    def test_no_api_key(self):
        """Test that the CLI fails gracefully if no API key is provided."""
        result = self.runner.invoke(
            setlistfm_cli.cli,
            ['search', 'artists', '--artist-name', 'AnyBand']
        )
        
        # Assert that the command failed
        self.assertNotEqual(result.exit_code, 0)
        # FIX 2: Check result.stderr instead of result.output for the error message
        self.assertIn("API key not found", result.stderr)

    @patch('setlistfm_cli.requests.get')
    def test_api_http_error(self, mock_get):
        """Test the CLI's handling of an HTTP error from the API."""
        # Mock a 404 Not Found error
        mock_response = Mock()
        mock_response.status_code = 404
        mock_response.reason = "Not Found"
        mock_response.text = "The requested resource was not found."
        mock_response.raise_for_status.side_effect = requests.exceptions.HTTPError(response=mock_response)
        mock_get.return_value = mock_response

        result = self.runner.invoke(
            setlistfm_cli.cli,
            ['--api-key', 'fakekey', 'artist', 'invalid-mbid']
        )

        # Assertions
        self.assertEqual(result.exit_code, 0) # The command exits cleanly
        self.assertIn("Error: 404 Not Found", result.stderr)
        self.assertIn("The requested resource was not found.", result.stderr)

if __name__ == '__main__':
    unittest.main()
