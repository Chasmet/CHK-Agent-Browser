"""Get the permanent Android signing keystore via verified GitHub OIDC.

No PAT, paid AI API or private Android signing key in this repository.
Only the exact main-branch APK workflow is allowed by Render.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

AUDIENCE="https://modeliseur-trellis-mcp.onrender.com/agentbrowser/ci/signing"
REQUEST_URL=os.environ["ACTIONS_ID_TOKEN_REQUEST_URL"]
REQUEST_TOKEN=os.environ["ACTIONS_ID_TOKEN_REQUEST_TOKEN"]
url=REQUEST_URL+("&" if "?" in REQUEST_URL else "?")+"audience="+urllib.parse.quote(AUDIENCE,safe="")
req=urllib.request.Request(url,headers={"Authorization":"Bearer "+REQUEST_TOKEN})
with urllib.request.urlopen(req,timeout=30) as response:
    identity=json.load(response)["value"]
req=urllib.request.Request(AUDIENCE,headers={"Authorization":"Bearer "+identity,
                                            "User-Agent":"CHK-Agent-Browser-CI/1.0"})
signing=None
for attempt in range(12):
    try:
        with urllib.request.urlopen(req,timeout=75) as response:
            signing=json.load(response)
        break
    except urllib.error.HTTPError as error:
        if error.code not in (404,502,503) or attempt==11:
            raise
        time.sleep(10)
if signing is None:
    raise RuntimeError("Signature persistante non disponible")
if (signing.get("alias")!="androiddebugkey" or
    signing.get("storePassword")!="android" or
    signing.get("keyPassword")!="android"):
    raise RuntimeError("Identité de signature inattendue")
folder=Path(".ci-signing")
folder.mkdir(exist_ok=True)
key=folder/"browser-release.jks"
key.write_bytes(base64.b64decode(signing["keystoreBase64"],validate=True))
key.chmod(0o600)
cert=subprocess.check_output(["keytool","-exportcert","-keystore",str(key),
                               "-storepass",signing["storePassword"],
                               "-alias",signing["alias"]])
fp=hashlib.sha256(cert).hexdigest()
if fp!="29e4bf6469e65741750b25f7ea50c97e7b538d5ead63bffebfd93cf197dd9335":
    raise RuntimeError("Refus de signer : identité Android non reconnue")
for value in (signing["storePassword"],signing["keyPassword"]):
    print("::add-mask::"+value)
with open(os.environ["GITHUB_ENV"],"a",encoding="utf-8") as out:
    out.write("CHK_KEYSTORE_FILE="+str(key.resolve())+"\n")
    out.write("CHK_KEYSTORE_PASSWORD="+signing["storePassword"]+"\n")
    out.write("CHK_KEY_ALIAS="+signing["alias"]+"\n")
    out.write("CHK_KEY_PASSWORD="+signing["keyPassword"]+"\n")
print("Signature Android permanente vérifiée, empreinte SHA256 publique "+fp[:12]+"…")
