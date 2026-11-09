// All 31 translated locales are indexed alongside English.
// Runs after the normal request router and does not block indexing on any locale.
var excluded = [];
function handler(event) {
  var response = event.response;
  var prefix = event.request.uri.split('/')[1].toLowerCase();
  if (excluded.indexOf(prefix) !== -1) {
    response.headers['x-robots-tag'] = {value: 'noindex, follow'};
  } else if (response.headers['x-robots-tag']) {
    delete response.headers['x-robots-tag'];
  }
  return response;
}
