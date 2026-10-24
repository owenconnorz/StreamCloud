// Run with: node StreamCloud/scripts/test-xfree-verification.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname,
  '../app/src/main/java/com/streamcloud/app/ui/screens/adult/XFreeLoginScreen.kt'), 'utf8');
function script(name) {
  return source.split(`val ${name} = """`)[1].split('""".trimIndent()')[0];
}
function control(label, options = {}) {
  return {
    innerText: label, disabled: false, clicks: 0,
    getAttribute: () => null, getClientRects: () => [{}],
    click() { this.clicks++; }, ...options,
  };
}
function continuePage(text, controls) {
  return vm.runInNewContext(script('XFREE_CONTINUE_VERIFICATION'), {
    document: { body: { innerText: text }, querySelectorAll: () => controls },
  });
}
const success = 'Account connection successful';
const button = control('Continue');
assert.equal(continuePage(success, [button]), true);
assert.equal(button.clicks, 1);
assert.equal(continuePage('Verify your age', [button]), false);
assert.equal(button.clicks, 1);
for (const item of [
  control('Continue', { disabled: true }),
  control('Continue', { getClientRects: () => [] }),
  control('Continue', { getAttribute: () => 'true' }),
  control('Start'),
]) {
  assert.equal(continuePage(success, [item]), false);
  assert.equal(item.clicks, 0);
}
assert.equal(continuePage(success, []), false);
const input = control('', { value: 'Continue' });
assert.equal(continuePage(success, [input]), true);
assert.equal(input.clicks, 1);
function signedIn(text, logout) {
  return vm.runInNewContext(script('XFREE_SIGNED_IN_CHECK'), {
    document: { body: { innerText: text }, querySelector: () => logout },
  });
}
assert.equal(signedIn('Log out', {}), true);
assert.equal(signedIn('Verify your age\nLog out', {}), false);
assert.equal(signedIn(success, null), false);
// Protect the provider callback from a regression to forced closing/reloading.
const handler = source.split('fun continueAfterVerification()')[1]
  .split('fun canConfirmSession')[0];
assert.doesNotMatch(handler, /\.loadUrl\(|destroyWebView\(/);
assert.match(handler, /evaluateJavascript\(XFREE_CONTINUE_VERIFICATION/);
// Account setup starts at home, not a forced /login entry point.
assert.match(source, /const val XFREE_ACCOUNT_START_URL = "https:\/\/www\.xfree\.com\/"/);
assert.match(source, /mainView\.loadUrl\(XFREE_ACCOUNT_START_URL\)/);
assert.doesNotMatch(source, /loadUrl\("https:\/\/www\.xfree\.com\/login/);
const app = fs.readFileSync(path.join(__dirname,
  '../app/src/main/java/com/streamcloud/app/ui/StreamCloudApp.kt'), 'utf8');
const route = app.split('composable("xfree-login")')[1].split('onBack =')[0];
assert.match(route, /onLoginSuccess = \{[\s\S]*setXFreeSignedIn\(true\)[\s\S]*nav\.popBackStack\(\)/);
// Playback has a separate WebView: enforce the shared navigation policy and
// popup transport wiring there too. Real Android callback behavior still needs
// device validation; these assertions catch source-level policy regressions.
const player = fs.readFileSync(path.join(__dirname,
  '../app/src/main/java/com/streamcloud/app/player/XFreePlayerScreen.kt'), 'utf8');
assert.match(player, /isAllowedXFreeLoginNavigation\(\s*url\.scheme, url\.host, mainFrame/);
assert.doesNotMatch(player, /isOfficialPageUrl\(/);
assert.match(player, /setSupportMultipleWindows\(true\)/);
assert.match(player, /override fun onCreateWindow\(/);
assert.match(player, /transport\.webView = popup\s+message\.sendToTarget\(\)/);
assert.match(player, /override fun onCloseWindow\(/);
assert.match(player, /popupWebView != null -> closePopup\(\)/);
assert.equal((player.match(/configureXFreeWebViewSession\(this, context\)/g) || []).length, 2);
assert.equal((player.match(/loadUrl\(/g) || []).length, 1,
  'Only initial video load is permitted; do not force navigation on verification success');
console.log('XFree continuation and sign-in detection regression checks passed.');
