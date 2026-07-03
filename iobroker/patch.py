filepath = "/opt/iobroker/node_modules/iobroker.matter/build/main.js"
content = open(filepath, "r", encoding="utf-8").read()
target = 'const nodejs_1 = require("@matter/nodejs");'
replacement = 'const nodejs_1 = require("@matter/nodejs");\nrequire("@matter/nodejs-ble");'
if target in content and 'require("@matter/nodejs-ble")' not in content:
    open(filepath, "w", encoding="utf-8").write(content.replace(target, replacement))
    print("PATCHED SUCCESSFULLY")
else:
    print("ALREADY PATCHED OR TARGET NOT FOUND")
