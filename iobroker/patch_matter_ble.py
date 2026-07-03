#!/usr/bin/env python3
import re, shutil, sys, os

f = "/opt/iobroker/node_modules/iobroker.matter/build/matter/ControllerNode.js"
s = open(f, encoding="utf-8").read()

# Restore original backup if it exists to have a clean slate
if os.path.exists(f + ".orig"):
    shutil.copy(f + ".orig", f)
    s = open(f, encoding="utf-8").read()

pat = re.compile(
    r"this\.#adapter\.matterEnvironment\.vars\.set\('ble\.enable', true\);.*?this\.#useBle = true;",
    re.DOTALL,
)
m = pat.search(s)
if not m:
    print("OLD BLOCK NOT FOUND - dumping init area:")
    i = s.find("init() {")
    print(s[i:i+900])
    sys.exit(1)

NEW = (
    "this.#adapter.matterEnvironment.vars.set('ble.enable', true);\n"
    "                const hciId = (this.#parameters.hciId === undefined || this.#parameters.hciId === '') ? undefined : parseInt(this.#parameters.hciId);\n"
    "                if (hciId !== undefined && hciId >= 0 && hciId <= 255) {\n"
    "                    this.#adapter.matterEnvironment.vars.set('ble.hci.id', hciId);\n"
    "                }\n"
    "                try {\n"
    "                    const { Ble } = require('@matter/protocol');\n"
    "                    const { NodeJsBle } = require('@matter/nodejs-ble');\n"
    "                    let __bleInst;\n"
    "                    Ble.get = () => (__bleInst || (__bleInst = new NodeJsBle(hciId !== undefined ? { hciId } : {})));\n"
    "                    this.#adapter.log.info('BLE driver registered via patch (hci=' + hciId + ')');\n"
    "                } catch (e) {\n"
    "                    this.#adapter.log.warn('BLE driver patch failed: ' + (e && e.message));\n"
    "                }\n"
    "                this.#useBle = true;"
)

shutil.copy(f, f + ".bak")
s2 = s[:m.start()] + NEW + s[m.end():]
open(f, "w", encoding="utf-8").write(s2)
print("CLEAN PATCH OK")
