// ============================================================================
// VERALTET / NICHT MEHR NOETIG (seit Umstellung der App auf WebSocket).
//
// Die SLZB-Matter-Connect-App spricht den Matter-Adapter jetzt DIREKT ueber die
// ioBroker-WebSocket-API (web-Adapter) an:
//     sendTo('matter.0','controllerCommissionDevice',{ manualCode, pollResponse:true })
// Dadurch werden WEDER der Adapter "Einfache RESTful API" (simple-api) NOCH dieses
// Skript benoetigt. Nur noch als Fallback/Doku aufbewahrt - kann geloescht werden.
// ============================================================================

// ioBroker-Skript: nimmt den Koppelungscode von der Android-App entgegen,
// stoesst die Koppelung im Matter-Adapter an und meldet das Ergebnis zurueck.
// States liegen konventionsgemaess unter 0_userdata.0.matter_connect.*
// Voraussetzung: Adapter "Einfache RESTful API" (simple-api) installiert, Port 8087.

const BASE = '0_userdata.0.matter_connect';

createState(BASE + '.pairing_code', '', {
    name: 'Matter Koppelungscode vom Handy',
    type: 'string', role: 'text', write: true
});

// Ergebnis-State, den die App pollt:
//   ''           = noch nichts / zurueckgesetzt
//   'processing' = Code angekommen, Koppelung laeuft
//   'success'    = Koppelung erfolgreich
//   'error: ...' = Koppelung fehlgeschlagen (mit Fehlertext)
createState(BASE + '.pairing_result', '', {
    name: 'Ergebnis der letzten Matter-Koppelung',
    type: 'string', role: 'text', write: true
});

// Matter-Adapter-Instanz, die die App vorgibt (z.B. "0" -> matter.0, "1" -> matter.1).
createState(BASE + '.instance', '0', {
    name: 'Matter-Adapter-Instanz fuer die Koppelung',
    type: 'string', role: 'text', write: true
});

on({id: BASE + '.pairing_code', change: 'any'}, function (obj) {
    let code = obj.state.val;
    if (code && code.trim() !== '') {
        // Ziel-Instanz auslesen (Default 0), z.B. matter.0
        let inst = getState(BASE + '.instance').val;
        if (inst === null || inst === undefined || ('' + inst).trim() === '') inst = '0';
        let adapter = 'matter.' + ('' + inst).trim();

        log('Empfange Matter-Koppelungscode vom Handy: ' + code + ' (Ziel: ' + adapter + ')');

        // App weiss dadurch sofort: angekommen, Koppelung laeuft.
        setState(BASE + '.pairing_result', 'processing', true);

        // Timeout auf 60s hochgesetzt (4. Parameter): der sendTo-Callback des JS-Adapters
        // wirft sonst nach 20s (Default) selbst {error:'timeout'} und verwirft danach die
        // echte Antwort von matter.0, obwohl das Commissioning oft erst nach ~24s fertig ist.
        sendTo(adapter, 'controllerCommissionDevice', { manualCode: code }, { timeout: 60000 }, function (result) {
            log('Ergebnis der Koppelung: ' + JSON.stringify(result));

            // Bestaetigt aus dem ioBroker-Log: matter.0 liefert bei Erfolg
            //   {"result":true,"nodeId":"..."}  (kein error-Feld).
            // Daher gilt Erfolg NUR bei result.result === true; alles andere ist ein Fehler.
            let ok = result && result.result === true;
            if (ok) {
                setState(BASE + '.pairing_result', 'success', true);
            } else {
                let msg = (result && (result.error || result.message)) || ('Adapter meldete: ' + JSON.stringify(result));
                setState(BASE + '.pairing_result', 'error: ' + msg, true);
            }

            // Code-State leeren (bereit fuer die naechste Koppelung).
            setState(BASE + '.pairing_code', '', true);
        });
    }
});
