"""Check captured plaintext DNS for destinations that must be handed to Tor, never local DNS."""
from pathlib import Path
import json,re,subprocess,sys
root=Path(sys.argv[1])
trace=subprocess.check_output(['tcpdump','-nn','-vv','-r',str(root/'tor-dns.pcap')],stderr=subprocess.DEVNULL,text=True)
(root/'tor-dns.txt').write_text(trace)
patterns=[r'\b[a-z0-9.-]+\.onion\.(?=\s|,|$)',r'\bcheck\.torproject\.org\.(?=\s|,|$)',r'\bdeliberately-unresolvable\.invalid\.(?=\s|,|$)']
leaks=[line for line in trace.splitlines() if any(re.search(p,line,re.I) for p in patterns)]
proof={'status':'failed' if leaks else 'passed','scope':'CI host plaintext UDP/TCP port 53; not an inspection of encrypted DNS','forbidden_queries':leaks,'captured_lines':len(trace.splitlines())}
(root/'tor-dns-proof.json').write_text(json.dumps(proof,indent=2)+'\n')
print(json.dumps(proof,indent=2))
if leaks:raise SystemExit('Tor destination appeared in local plaintext DNS')
