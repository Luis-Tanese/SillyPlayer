.pragma library

var active = [];
var waiting = [];
var pumping = false;
function pump() {
    if (pumping) return;
    pumping = true;
    try {
        while (active.length < 6 && waiting.length) {
            var row = waiting.shift();
            active.push(row);
            row.beginAttempt();
        }
    } finally { pumping = false; }
}
function request(row) {
    if (active.indexOf(row) < 0 && waiting.indexOf(row) < 0) waiting.push(row);
    pump();
}
function release(row) {
    active = active.filter(function(item) { return item !== row; });
    waiting = waiting.filter(function(item) { return item !== row; });
    pump();
}
