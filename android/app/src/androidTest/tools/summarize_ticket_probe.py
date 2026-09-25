"""Turn local probe dumps into conservative, reviewable fixture drafts.

Only explicitly allowlisted public event/vendor words survive from real inputs.
Everything else remains in the private raw dump. No credentials are printed.
Usage: python summarize_ticket_probe.py PRIVATE_RAW_DIR DRAFT_OUTPUT_DIR
"""
import collections
import json
import pathlib
import re
import subprocess
import sys

PUBLIC = {
    "ticketline", "mork water", "skambankt", "parkteatret", "parkteatret scene",
    "dumdumboys", "dumdumboys - xl [romertallførti]", "dumdumboys – xl [romertallførti]",
    "eventim", "eventim.no", "billettservice", "ticketmaster", "ticketmaster.no",
    "rockefeller", "rockefeller, oslo", "sentrum scene", "oslo spektrum", "oslo",
    "john dee", "john dee, oslo", "vulkan arena", "blå",
    "your ticket", "ticket", "e-ticket", "ticket number:", "billet service",
    "kjøpte bill. refunderes ikke", "del en opplevelse!", "ståplass/stående",
    "ståplass/ståplass", "ståplass/standing",
}
DATE = re.compile(r"(?:\d{1,2}[./-]\d{1,2}[./-]\d{4}|\d{4}-\d{2}-\d{2}|\d{1,2}\.?\s+(?:jan|feb|mar|apr|mai|jun|jul|aug|sep|okt|nov|des)\.?\s+\d{4})", re.I)


def descriptor(value):
    return None if value is None else {k: value[k] for k in ('sha256', 'length', 'isUtf8', 'charset')}


def redact(text, synthetic=False, payloads=()):
    if text is None:
        return None
    for payload in sorted(set(payloads), key=len, reverse=True):
        if payload:
            text = text.replace(payload, '[BARCODE_PAYLOAD]')
    if synthetic:
        return text
    return '\n'.join(line if line.strip().casefold() in PUBLIC or DATE.fullmatch(line.strip())
                     else '[PERSON_NAME]' if re.search(r'\b(?:navn|name)\b', line, re.I)
                     else '[ORDER_OR_REFERENCE]' if re.search(r'\b(?:order|ordre|ref|bestilling)\b|\d{5,}', line, re.I)
                     else '[REDACTED_UNREVIEWED_TEXT]' for line in text.split('\n'))


def summarize(path):
    raw = json.loads(path.read_text())
    synthetic = path.stem.startswith('synthetic-')
    results = [(page['page'], render['dpi'], run['mode'], result)
               for page in raw['pages'] for render in page['renders']
               for run in render['barcodeRuns'] for result in run['results']]
    payloads = [r['text'] for _, _, _, r in results]
    safe = lambda t: redact(t, synthetic, payloads)
    text = [line for page in raw['pages'] for content in page.get('textLayer', [])
            if isinstance(content, dict) for line in content['lines']]
    ocr = [line['text'] for page in raw['pages'] for render in page['renders'] if render['dpi'] == 200
           for block in render.get('ocrBlocks', []) for line in block['lines']]
    unique = {}
    for page, dpi, mode, result in results:
        key = (result['format'], result['textUtf8']['sha256'])
        if key not in unique:
            trips = {}
            for name, trip in result['roundTrips'].items():
                trips[name] = None if trip is None else {
                    k: v for k, v in trip.items()
                    if k in ('status', 'format', 'errorType', 'textEqualsOriginal', 'rawEqualsOriginal', 'byteSegmentsEqualOriginal', 'ms')}
            unique[key] = {'format': result['format'], 'payload': descriptor(result['textUtf8']),
                           'rawBytes': descriptor(result['rawBytes']),
                           'rawEqualsTextUtf8': result['rawEqualsTextUtf8'],
                           'byteSegments': [descriptor(v) for v in result['byteSegments']],
                           'symbologyIdentifier': result['symbologyIdentifier'],
                           'roundTrips': trips, 'occurrences': []}
        unique[key]['occurrences'].append({'page': page, 'dpi': dpi, 'mode': mode,
                                           'pointBoundsPx': result['pointBounds']})
    actual_redraw = raw.get('actualMainRedraw480', {})
    for key, barcode in unique.items():
        measured = actual_redraw.get(':'.join(key))
        if measured is not None:
            barcode['actualMainRedraw480'] = {k: v for k, v in measured.items() if k != 'decodedTextUtf8'}
            barcode['actualMainRedraw480']['decodedTextUtf8'] = descriptor(measured.get('decodedTextUtf8'))
    parsers = {}
    for name, parsed in raw['parser'].items():
        parsers[name] = {**parsed, 'artist': safe(parsed['artist']), 'venue': safe(parsed['venue'])}
    readings = [{'origin': origin, 'lines': [safe(s) for s in lines]}
                for origin, lines in [('textLayer', text), ('ocr', ocr)] if any(s.strip() for s in lines)]
    expected = {field: {'value': '', 'support': ''} for field in ['artist', 'venue', 'date']}
    if synthetic:
        date = {'synthetic-date-norwegian': '28-11-2026', 'synthetic-date-numeric': '24-09-2026',
                'synthetic-date-english': '24-06-2026'}.get(path.stem, '25-09-2026')
        for field, value in [('artist', 'Ocean Colour Scene'), ('venue', 'Auditorio Marina Norte, Valencia'), ('date', date)]:
            support = ('ocr' if path.stem == 'synthetic-flattened' or
                       (path.stem == 'synthetic-artist-image' and field == 'artist') else 'both')
            expected[field] = {'value': value, 'support': support}
    elif path.stem == 'real-eticket-2':
        expected['artist']['value'] = 'Skambankt'
        expected['venue']['value'] = 'Parkteatret Scene'
    elif path.stem == 'real-eventim-2':
        expected['artist']['value'] = 'Dumdumboys'
        expected['date']['value'] = '28-11-2026'
    elif path.stem == 'phone-future':
        expected['artist']['value'] = 'MORK WATER'
        expected['date']['value'] = '28-09-2026'
    counts = {f'{dpi}/{mode}': len({(r['format'], r['textUtf8']['sha256']) for _, d, m, r in results if d == dpi and m == mode})
              for dpi in [200, 300] for mode in ['unhinted', 'tryHarder', 'multiple']}
    fixture = {'id': path.stem, 'sourceKind': 'synthetic' if synthetic else 'local-input-provenance-unverified',
               'readings': readings, 'barcodes': list(unique.values()), 'expected': expected,
               'observedParser': parsers,
               'notes': ['Draft shape per #526; expected values/support are proposals, not a new shared spec.',
                         'OCR reading is 200 dpi. Original item boundaries/bounds and 300 dpi readings stay in the local dump.',
                         'Real/unverified inputs use a conservative text allowlist; placeholders also cover unreviewed harmless text.',
                         'Payload descriptor describes decoded text encoded as UTF-8, not proof of original barcode encoding.',
                         'Point bounds are the extrema of decoder result points, not guaranteed full symbol/quiet-zone bounds.']}
    if path.stem in ('synthetic-qr_code', 'synthetic-flattened'):
        fixture['matchingExample'] = {'setlistFmId': '1b498dc4',
            'url': 'https://www.setlist.fm/setlist/ocean-colour-scene/2026/auditorio-marina-norte-valencia-spain-1b498dc4.html',
            'artist': 'Ocean Colour Scene', 'venue': 'Auditorio Marina Norte', 'city': 'Valencia',
            'country': 'Spain', 'date': '25-09-2026', 'festival': 'Visor Fest',
            'expectedOutcome': 'linked', 'status': 'proposed future matcher fixture; matching was not run'}
    stats = {'id': path.stem, 'pages': raw['pageCount'], 'counts': counts,
             'distinctDetected': len(unique), 'formats': sorted({k[0] for k in unique}),
             'currentKeeps': int(raw['currentExtractionPayload'] is not None),
             'textLayerLines': len(text), 'ocrLines': len(ocr), 'lineOrdersEqual': text == ocr,
             'nonemptyTextLayerLines': sum(bool(s.strip()) for s in text),
             'ticketlineInTextLayer': any('TICKETLINE' in s.upper() for s in text),
             'morkWaterInTextLayer': any('MORK WATER' in s.upper() for s in text),
             'parser': parsers, 'totalMs': raw['totalMs'], 'sampledPeakPssKb': raw['sampledPeakPssKb'],
             'timings': {stage: round(sum(render.get(stage, 0) for p in raw['pages'] for render in p['renders']), 2)
                         for stage in ['renderMs', 'ocrMs']},
             'barcodeDecodeMs': round(sum(run['decodeMs'] for p in raw['pages'] for render in p['renders'] for run in render['barcodeRuns']), 2),
             'textLayerMs': round(sum(p['textLayerMs'] for p in raw['pages']), 2),
             'perPage': [{'page': p['page'], 'textContents': len(p.get('textLayer', [])),
                          'runs': {f"{r['dpi']}/{run['mode']}": len(run['results'])
                                   for r in p['renders'] for run in r['barcodeRuns']}}
                         for p in raw['pages']],
             'actualMainRedraw480': [b.get('actualMainRedraw480') for b in unique.values()],
             'roundTrips': [dict(format=b['format'], **b['roundTrips']) for b in unique.values()],
             'errors': [r.get('ocrError') for p in raw['pages'] for r in p['renders'] if r.get('ocrError')]}
    pdf = path.parent.parent / 'inputs' / raw['file']
    if pdf.exists():
        reference = subprocess.check_output(['pdftotext', str(pdf), '-'], text=True)
        def tokens(s):
            return collections.Counter(re.findall(r'\w+', s.casefold()))
        ref = tokens(reference)
        stats['hostTextLayerWordCount'] = sum(ref.values())
        for name, lines in [('androidTextLayer', text), ('ocr', ocr)]:
            captured = tokens('\n'.join(lines))
            stats[name + 'WordCoverageVsPoppler'] = round(sum((ref & captured).values()) / sum(ref.values()), 4) if ref else None
    stats['knownFieldChecks'] = {}
    for name, p in raw['parser'].items():
        stats['knownFieldChecks'][name] = {
            field: None if not e['value'] else bool(p[field] and p[field].strip().casefold() == e['value'].casefold())
            for field, e in expected.items()}
    return fixture, stats


if __name__ == '__main__':
    source, output = map(pathlib.Path, sys.argv[1:])
    output.mkdir(parents=True, exist_ok=True)
    stats = []
    for path in sorted(source.glob('*.json')):
        fixture, row = summarize(path)
        (output / path.name).write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + '\n')
        stats.append(row)
    # This summary contains only allowlisted text and barcode metadata, never payloads.
    (output.parent / 'measurements.json').write_text(json.dumps(stats, ensure_ascii=False, indent=2) + '\n')
    print(f'Wrote {len(stats)} redacted fixture drafts')
