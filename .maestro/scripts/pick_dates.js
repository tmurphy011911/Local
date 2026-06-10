// Computes the two day-cell content descriptions the Material3 DateRangePicker
// (Android) exposes for a start/end date a few days out from "now". Building the
// label from arrays (not Intl) keeps it deterministic across JS engines.
// Format must match Material3's day contentDescription: "Saturday, June 13, 2026".
function desc(d) {
  var days = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  var months = ['January', 'February', 'March', 'April', 'May', 'June',
                'July', 'August', 'September', 'October', 'November', 'December'];
  return days[d.getDay()] + ', ' + months[d.getMonth()] + ' ' + d.getDate() + ', ' + d.getFullYear();
}

var start = new Date();
start.setDate(start.getDate() + 3);
var end = new Date();
end.setDate(end.getDate() + 4); // a clean 2-day range (two distinct taps the range picker accepts)

output.startDesc = desc(start);
output.endDesc = desc(end);
