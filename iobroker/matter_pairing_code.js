// ioBroker-Skript: nimmt den Koppelungscode von der Android-App entgegen
// und stößt die Koppelung im Matter-Adapter an.
// Voraussetzung: Adapter "Einfache RESTful API" (simple-api) installiert, Port 8087.

createState('matter_pairing_code', '', {
    name: 'Matter Koppelungscode vom Handy',
    type: 'string',
    role: 'text',
    write: true
});

on({id: 'javascript.0.matter_pairing_code', change: 'any'}, function (obj) {
    let code = obj.state.val;
    if (code && code.trim() !== '') {
        log('Empfange Matter-Koppelungscode vom Handy: ' + code);
        sendTo('matter.0', 'controllerCommissionDevice', { manualCode: code }, function (result) {
            log('Ergebnis der Koppelung: ' + JSON.stringify(result));
            setState('javascript.0.matter_pairing_code', '', true); // State leeren
        });
    }
});
