import os
import configparser
import json
import sys
import click
import requests

# --- Configuration ---
def get_api_key(api_key_param):
    """
    Get API key from param, env var, or config file.
    Order of precedence:
    1. --api-key parameter
    2. SETLISTFM_API_KEY environment variable
    3. .setlistfmcli config file
    """
    if api_key_param:
        return api_key_param

    env_key = os.environ.get('SETLISTFM_API_KEY')
    if env_key:
        return env_key

    config = configparser.ConfigParser()
    config.read(os.path.expanduser('~/.setlistfmcli'))
    if 'setlistfm' in config and 'api_key' in config['setlistfm']:
        return config['setlistfm']['api_key']

    return None

def get_accept_header(accept_header_param):
    """Get Accept header from param, env var, or config file."""
    if accept_header_param:
        return accept_header_param
    
    env_header = os.environ.get('SETLISTFM_ACCEPT_HEADER')
    if env_header:
        return env_header

    config = configparser.ConfigParser()
    config.read(os.path.expanduser('~/.setlistfmcli'))
    if 'setlistfm' in config and 'accept_header' in config['setlistfm']:
        return config['setlistfm']['accept_header']
    
    return 'application/json' # Default value

# --- API Request Logic ---
def make_api_request(api_key, accept_header, endpoint, params=None):
    """Makes a request to the setlist.fm API and handles errors."""
    base_url = "https://api.setlist.fm/rest/1.0"
    headers = {
        'x-api-key': api_key,
        'Accept': accept_header
    }
    
    # Filter out None values from params
    if params:
        params = {k: v for k, v in params.items() if v is not None}
    else:
        params = {}

    try:
        response = requests.get(f"{base_url}/{endpoint}", headers=headers, params=params)
        response.raise_for_status()  # Raises an HTTPError for bad responses (4xx or 5xx)
        return response.json()
    except requests.exceptions.HTTPError as err:
        click.echo(f"Error: {err.response.status_code} {err.response.reason}", err=True)
        click.echo(err.response.text, err=True)
    except requests.exceptions.RequestException as e:
        click.echo(f"A network error occurred: {e}", err=True)
    return None

# --- HTML Generation ---
def generate_html_report(setlists, user_id):
    """Generates an HTML report from a list of setlists."""
    html = f"""
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Concert History for {user_id}</title>
        <style>
            body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; line-height: 1.6; background-color: #f4f4f4; color: #333; margin: 0; padding: 20px; }}
            .container {{ max-width: 800px; margin: auto; background: #fff; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }}
            h1 {{ color: #1DB954; }}
            .concert {{ border-bottom: 1px solid #eee; padding: 15px 0; }}
            .concert:last-child {{ border-bottom: none; }}
            .date {{ font-weight: bold; color: #555; }}
            .artist {{ font-size: 1.2em; font-weight: bold; color: #1DB954; }}
            .venue {{ font-style: italic; color: #777; }}
        </style>
    </head>
    <body>
        <div class="container">
            <h1>Concert History for {user_id}</h1>
    """

    if not setlists:
        html += "<p>No attended concerts found.</p>"
    else:
        for setlist in sorted(setlists, key=lambda x: x['eventDate'], reverse=True):
            artist = setlist.get('artist', {}).get('name', 'N/A')
            venue = setlist.get('venue', {}).get('name', 'N/A')
            city = setlist.get('venue', {}).get('city', {}).get('name', 'N/A')
            country = setlist.get('venue', {}).get('city', {}).get('country', {}).get('name', 'N/A')
            event_date = setlist.get('eventDate', 'N/A')

            html += f"""
            <div class="concert">
                <div class="date">{event_date}</div>
                <div class="artist">{artist}</div>
                <div class="venue">{venue} in {city}, {country}</div>
            </div>
            """

    html += """
        </div>
    </body>
    </html>
    """
    return html

# --- CLI Structure ---
@click.group()
@click.option('--api-key', help='Your setlist.fm API key.')
@click.option('--accept-header', help='The Accept header for the request.')
@click.pass_context
def cli(ctx, api_key, accept_header):
    """A CLI tool for the setlist.fm API."""
    ctx.ensure_object(dict)
    
    resolved_api_key = get_api_key(api_key)
    if not resolved_api_key:
        click.echo("Error: API key not found. Please provide it via --api-key, SETLISTFM_API_KEY env var, or in ~/.setlistfmcli", err=True)
        sys.exit(1)

    ctx.obj['API_KEY'] = resolved_api_key
    ctx.obj['ACCEPT_HEADER'] = get_accept_header(accept_header)

@cli.group()
def search():
    """Search for artists, etc."""
    pass

@search.command(name='artists')
@click.option('--artist-name', help='The name of the artist.')
@click.option('--artist-mbid', help='The MusicBrainz ID of the artist.')
@click.option('-p', type=int, help='The page number.')
@click.pass_context
def search_artists(ctx, artist_name, artist_mbid, p):
    """Search for an artist."""
    params = {'artistName': artist_name, 'artistMbid': artist_mbid, 'p': p}
    result = make_api_request(ctx.obj['API_KEY'], ctx.obj['ACCEPT_HEADER'], 'search/artists', params)
    if result:
        click.echo(json.dumps(result, indent=2))

@cli.command()
@click.argument('mbid')
@click.pass_context
def artist(ctx, mbid):
    """Get a specific artist by their MusicBrainz ID."""
    result = make_api_request(ctx.obj['API_KEY'], ctx.obj['ACCEPT_HEADER'], f'artist/{mbid}')
    if result:
        click.echo(json.dumps(result, indent=2))

@cli.command(name='user-attended')
@click.argument('user_id')
@click.pass_context
def user_attended(ctx, user_id):
    """Get a user's attended concerts and generate an HTML report."""
    all_setlists = []
    page = 1
    
    while True:
        endpoint = f'user/{user_id}/attended'
        params = {'p': page}
        data = make_api_request(ctx.obj['API_KEY'], ctx.obj['ACCEPT_HEADER'], endpoint, params)
        
        if not data or 'setlist' not in data or not data['setlist']:
            break
            
        all_setlists.extend(data['setlist'])
        
        total = data.get('total', 0)
        items_per_page = data.get('itemsPerPage', 20)
        
        if (page * items_per_page) >= total:
            break
        
        page += 1

    html_output = generate_html_report(all_setlists, user_id)
    click.echo(html_output)

if __name__ == '__main__':
    cli(obj={})
