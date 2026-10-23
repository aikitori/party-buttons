import { apply, setLang, type Lang } from "./i18n";

document.querySelectorAll<HTMLButtonElement>("[data-lang]").forEach((btn) => {
  btn.addEventListener("click", () => setLang(btn.dataset.lang as Lang));
});

apply();
