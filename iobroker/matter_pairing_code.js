// ioBroker-Skript: nimmt den Koppelungscode von der Android-App entgegen,
// stoesst die Koppelung im Matter-Adapter an und meldet das Ergebnis zurueck,
// damit die App live sehen kann, ob es geklappt hat.
// Voraussetzung: Adapter "Einfache RESTful API" (simple-api) installiert, Port 8087.

createState('matter_pairing_code', '', {
    name: 'Matter Koppelungscode vom Handy',
    type: 'string',
    role: 'text',
    write: true
});

// Ergebnis-State, den die App pollt:
//   ''           = noch nichts / zurueckgesetzt
//   'processing' = Code angekommen, Koppelung laeuft
//   'success'    = Koppelung erfolgreich
//   'error: ...' = Koppelung fehlgeschlagen (mit Fehlertext)
createState('matter_pairing_result', '', {
    name: 'Ergebnis der letzten Matter-Koppelung',
    type: 'string',
    role: 'text',
    write: true
});

on({id: 'javascript.0.matter_pairing_code', change: 'any'}, function (obj) {
    let code = obj.state.val;
    if (code && code.trim() !== '') {
        log('Empfange Matter-Koppelungscode vom Handy: ' + code);

        // App weiss dadurch sofort: angekommen, Koppelung laeuft.
        setState('javascript.0.matter_pairing_result', 'processing', true);

        sendTo('matter.0', 'controllerCommissionDevice', { manualCode: code }, function (result) {
            log('Ergebnis der Koppelung: ' + JSON.stringify(result));

            // Erfolg/Fehler aus dem Ergebnis ableiten.
            // Bestaetigt aus dem ioBroker-Log: matter.0 liefert bei Erfolg
            //   {"result":true,"nodeId":"..."}  (kein error-Feld).
            // Daher gilt Erfolg NUR bei result.result === true; alles andere ist ein Fehler.
            let ok = result && result.result === true;
            if (ok) {
                setState('javascript.0.matter_pairing_result', 'success', true);
            } else {
                let msg = (result && (result.error || result.message)) || ('Adapter meldete: ' + JSON.stringify(result));
                setState('javascript.0.matter_pairing_result', 'error: ' + msg, true);
            }

            // Code-State leeren (bereit fuer die naechste Koppelung).
            setState('javascript.0.matter_pairing_code', '', true);
        });
    }
});
