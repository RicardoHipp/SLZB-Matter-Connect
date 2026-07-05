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

            // Erfolg/Fehler bestmoeglich aus dem Ergebnis ableiten.
            // HINWEIS: Die genaue Struktur von "result" kann je nach matter.0-Version
            // abweichen. Falls Erfolg faelschlich als Fehler erkannt wird (oder umgekehrt),
            // oben die geloggte Struktur ansehen und die folgende Zeile anpassen.
            let ok = result && !result.error;
            if (ok) {
                setState('javascript.0.matter_pairing_result', 'success', true);
            } else {
                let msg = (result && (result.error || result.message)) || 'unbekannter Fehler';
                setState('javascript.0.matter_pairing_result', 'error: ' + msg, true);
            }

            // Code-State leeren (bereit fuer die naechste Koppelung).
            setState('javascript.0.matter_pairing_code', '', true);
        });
    }
});
