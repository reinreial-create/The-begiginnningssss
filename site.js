(() => {
  const saved = localStorage.getItem('rr-lang');
  const preferred = saved || 'et';
  document.body.dataset.lang = preferred;
  document.documentElement.lang = preferred;

  const syncButtons = () => {
    document.querySelectorAll('[data-lang-btn]').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.langBtn === document.body.dataset.lang);
    });
  };
  syncButtons();

  document.querySelectorAll('[data-lang-btn]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const lang = btn.dataset.langBtn;
      document.body.dataset.lang = lang;
      document.documentElement.lang = lang;
      localStorage.setItem('rr-lang', lang);
      syncButtons();
    });
  });

  const footerRight = document.querySelector('.footer .footer-inner > div:last-child');
  if (footerRight && !footerRight.querySelector('a[href="privacy.html"]')) {
    footerRight.append(document.createTextNode(' · '));
    const privacy = document.createElement('a');
    privacy.href = 'privacy.html';
    privacy.textContent = document.body.dataset.lang === 'en' ? 'Privacy & terms' : 'Privaatsus & tingimused';
    footerRight.append(privacy);
  }
})();