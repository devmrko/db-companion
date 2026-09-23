// Only the wallet selector is submitted with GET; credentials stay in the separate POST form.
export function wireWalletSelector(form, login) {
  const select = form.querySelector('[data-wallet-select]');
  const apply = form.querySelector('[data-wallet-apply]');
  const button = login.querySelector('button[type="submit"]');
  const alias = login.querySelector('[name="tnsAlias"]');
  const username = login.querySelector('[name="username"]');
  const password = login.querySelector('[name="password"]');
  const boundWallet = login.querySelector('[name="walletId"]');
  apply.hidden = true;
  function clear() {
    alias.value = '';
    username.value = '';
    password.value = '';
    alias.disabled = true;
    button.disabled = true;
  }
  select.addEventListener('change', () => {
    clear();
    if (select.value) form.requestSubmit();
    else apply.hidden = false;
  });
  // Back/forward form restoration must not submit an old hidden wallet with a new visible selection.
  login.addEventListener('submit', event => {
    if (!select.value || select.value !== boundWallet.value) {
      event.preventDefault();
      clear();
      if (select.value) form.requestSubmit();
    }
  });
}

if (typeof document !== 'undefined') {
  const form = document.querySelector('[data-wallet-form]');
  const login = document.querySelector('[data-login-form]');
  if (form && login) wireWalletSelector(form, login);
}
