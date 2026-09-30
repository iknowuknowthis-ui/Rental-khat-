var exec = require('cordova/exec');

module.exports = {
  requestPermission: function (ok, fail) { exec(ok, fail, 'SmsPayment', 'requestPermission', []); },
  hasPermission: function (ok, fail) { exec(ok, fail, 'SmsPayment', 'hasPermission', []); },
  listen: function (onNew, fail) { exec(onNew, fail, 'SmsPayment', 'listen', []); },
  getPending: function (ok, fail) { exec(ok, fail, 'SmsPayment', 'getPending', []); },
  shareFile: function (base64, filename, mime, title, ok, fail) {
    exec(ok, fail, 'SmsPayment', 'shareFile', [base64, filename, mime, title]);
  }
};
