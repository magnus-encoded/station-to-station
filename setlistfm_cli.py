import os
import configparser
import json
import sys
import click
import requests
import time
from datetime import datetime # Import the datetime module

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
    """Makes a request to the setlist.fm API and handles errors with retries."""
    base_url = "https://api.setlist.fm/rest/1.0"
    headers = {
        'x-api-key': api_key,
        'Accept': accept_header
    }
    
    if params:
        params = {k: v for k, v in params.items() if v is not None}
    else:
        params = {}
    
    max_retries = 3
    backoff_factor = 2
    delay = 1

    for attempt in range(max_retries):
        try:
            response = requests.get(f"{base_url}/{endpoint}", headers=headers, params=params)
            response.raise_for_status()
            return response.json()
        except requests.exceptions.HTTPError as err:
            if err.response.status_code == 429 or err.response.status_code >= 500:
                if attempt < max_retries - 1:
                    click.echo(f"Warning: Received status {err.response.status_code}. Retrying in {delay}s...", err=True)
                    time.sleep(delay)
                    delay *= backoff_factor
                else:
                    click.echo(f"Error: Received status {err.response.status_code} after {max_retries} attempts.", err=True)
                    click.echo(err.response.text, err=True)
                    return None
            else:
                click.echo(f"Error: {err.response.status_code} {err.response.reason}", err=True)
                click.echo(err.response.text, err=True)
                return None
        except requests.exceptions.RequestException as e:
            click.echo(f"A network error occurred: {e}", err=True)
            return None
    return None

# --- HTML Generation ---
def generate_html_report(setlists, user_id, limit_reached, max_requests):
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
            .warning {{ background-color: #fff3cd; border: 1px solid #ffeeba; color: #856404; padding: 10px; border-radius: 5px; margin-top: 20px; }}
        </style>
    </head>
    <body>
        <div class="container">
            <h1>Concert History for {user_id}</h1>
    """

    if limit_reached:
        html += f"<p class='warning'><b>Note:</b> This report may be incomplete because the run was configured to stop after a maximum of {max_requests} requests.</p>"

    if not setlists:
        html += "<p>No attended concerts found.</p>"
    else:
        # --- THE FIX ---
        # Helper function to parse date string into a real date object for sorting.
        # This handles potential errors if a date is missing or malformed.
        def sort_key(setlist):
            try:
                # The API format is DD-MM-YYYY
                return datetime.strptime(setlist.get('eventDate'), '%d-%m-%Y')
            except (ValueError, TypeError):
                # If date is invalid, treat it as the oldest possible date for sorting.
                return datetime.min

        # Sort using the new key function for correct chronological order.
        for setlist in sorted(setlists, key=sort_key, reverse=True):
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
@click.option('--rate-limit', type=float, default=1.0, help='Requests per second limit.', show_default=True)
@click.option('--max-requests', type=int, default=1440, help='Maximum requests per run to avoid hitting daily API limits.', show_default=True)
@click.pass_context
def user_attended(ctx, user_id, rate_limit, max_requests):
    """Get a user's attended concerts and generate an HTML report."""
    all_setlists = []
    page = 1
    limit_hit = False
    
    sleep_duration = 1.0 / rate_limit if rate_limit > 0 else 0
    
    click.echo(f"Fetching attended concerts for {user_id}...", err=True)
    click.echo(f"Rate limit: {rate_limit}/sec, Max requests: {max_requests}", err=True)

    while page <= max_requests:
        endpoint = f'user/{user_id}/attended'
        params = {'p': page}
        
        click.echo(f" - Fetching page {page}...", err=True)
        data = make_api_request(ctx.obj['API_KEY'], ctx.obj['ACCEPT_HEADER'], endpoint, params)
        
        if not data:
            click.echo("Stopping due to API error.", err=True)
            break
            
        if 'setlist' not in data or not data['setlist']:
            break
            
        all_setlists.extend(data['setlist'])
        
        total = data.get('total', 0)
        items_per_page = data.get('itemsPerPage', 20)
        
        if (page * items_per_page) >= total:
            break
        
        page += 1
        
        if page > max_requests:
            limit_hit = True
            click.echo(f"Stopping: Reached max requests limit of {max_requests}.", err=True)
            break
        
        time.sleep(sleep_duration)

    click.echo(f"Finished fetching. Found {len(all_setlists)} concerts.", err=True)
    html_output = generate_html_report(all_setlists, user_id, limit_hit, max_requests)
    click.echo(html_output)

if __name__ == '__main__':
    cli(obj={})