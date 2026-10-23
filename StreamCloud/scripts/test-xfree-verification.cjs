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
console.log('XFree continuation and sign-in detection regression checks passed.');
