import os
import json
import click
import configparser
import requests

# Default configuration
CONFIG_FILE = os.path.expanduser('~/.setlistfmcli')
DEFAULT_ACCEPT_HEADER = 'application/json'
API_BASE_URL = 'https://api.setlist.fm/rest/1.0'

def get_config():
    """Reads configuration from file and environment variables."""
    config = configparser.ConfigParser()
    config.read(CONFIG_FILE)

    if 'setlistfm' not in config:
        config['setlistfm'] = {}

    config_dict = {
        'api_key': os.environ.get('SETLISTFM_API_KEY', config['setlistfm'].get('api_key')),
        'accept_header': os.environ.get('SETLISTFM_ACCEPT_HEADER', config['setlistfm'].get('accept_header', DEFAULT_ACCEPT_HEADER))
    }
    return config_dict

def make_api_request(endpoint, params, api_key, accept_header):
    """Makes a request to the setlist.fm API using the requests library."""
    if not api_key or 'YOUR_API_KEY_HERE' in api_key:
        raise click.UsageError("API key not found or not set. Please provide it via config file, environment variable, or --api-key option.")

    headers = {
        'x-api-key': api_key,
        'Accept': accept_header
    }
    
    # Filter out None values from params before making the request
    filtered_params = {k: v for k, v in params.items() if v is not None}
    
    url = f"{API_BASE_URL}/{endpoint}"

    try:
        response = requests.get(url, headers=headers, params=filtered_params)
        response.raise_for_status()  # Raises an HTTPError for bad responses (4xx or 5xx)
        return response.json()
    except requests.exceptions.HTTPError as e:
        click.echo(f"Error: {e.response.status_code} {e.response.reason}", err=True)
        click.echo(e.response.text, err=True)
        return None
    except requests.exceptions.RequestException as e:
        click.echo(f"Error: Could not connect to API. {e}", err=True)
        return None

@click.group()
@click.option('--api-key', help='Your setlist.fm API key. Overrides config and environment variables.')
@click.option('--accept-header', help=f'Accept header for the request. Overrides config and environment variables.')
@click.pass_context
def cli(ctx, api_key, accept_header):
    """A command-line interface for the setlist.fm API."""
    ctx.ensure_object(dict)
    config = get_config()
    ctx.obj['API_KEY'] = api_key or config['api_key']
    ctx.obj['ACCEPT_HEADER'] = accept_header or config['accept_header']

@cli.group()
def search():
    """Search for artists, cities, countries, etc."""
    pass

@search.command('artists')
@click.option('--artist-name', help="The artist's name")
@click.option('--artist-mbid', help="The artist's Musicbrainz Identifier")
@click.option('-p', '--p', type=int, help='The page number')
@click.pass_context
def search_artists(ctx, artist_name, artist_mbid, p):
    """Search for an artist."""
    params = {'artistName': artist_name, 'artistMbid': artist_mbid, 'p': p}
    result = make_api_request('search/artists', params, ctx.obj['API_KEY'], ctx.obj['ACCEPT_HEADER'])
    if result:
        click.echo(json.dumps(result, indent=4))

@cli.command('artist')
@click.argument('mbid')
@click.pass_context
def get_artist(ctx, mbid):
    """Get a specific artist by their Musicbrainz Identifier (MBID)."""
    result = make_api_request(f'artist/{mbid}', {}, ctx.obj['API_KEY'], ctx.obj['ACCEPT_HEADER'])
    if result:
        click.echo(json.dumps(result, indent=4))

if __name__ == '__main__':
    cli()
