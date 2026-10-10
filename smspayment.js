var exec = require('cordova/exec');

module.exports = {
  requestPermission: function (ok, fail) { exec(ok, fail, 'SmsPayment', 'requestPermission', []); },
  hasPermission: function (ok, fail) { exec(ok, fail, 'SmsPayment', 'hasPermission', []); },
  listen: function (onNew, fail) { exec(onNew, fail, 'SmsPayment', 'listen', []); },
  diagnose: function (ok, fail) { exec(ok, fail, 'SmsPayment', 'diagnose', []); },
  scanInbox: function (days, ok, fail) { exec(ok, fail, 'SmsPayment', 'scanInbox', [days]); },
  openAutostart: function (ok, fail) { exec(ok, fail, 'SmsPayment', 'openAutostart', []); },
  syncCustomers: function (json, ok, fail) { exec(ok, fail, 'SmsPayment', 'syncCustomers', [json]); },
  getPending: function (ok, fail) { exec(ok, fail, 'SmsPayment', 'getPending', []); },
  shareFile: function (base64, filename, mime, title, ok, fail) {
    exec(ok, fail, 'SmsPayment', 'shareFile', [base64, filename, mime, title]);
  }
};
