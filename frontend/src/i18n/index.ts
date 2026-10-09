import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import ko, { type Messages } from "./ko";

declare module "i18next" {
  interface CustomTypeOptions {
    defaultNS: "translation";
    resources: { translation: Messages };
  }
}

// Korean is the only locale shipped for now; English is a "could" in features.csv.
// Adding it means one more resource file with the same keys (type-checked against Messages).
void i18n.use(initReactI18next).init({
  resources: { ko: { translation: ko } },
  lng: "ko",
  fallbackLng: "ko",
  interpolation: { escapeValue: false },
  returnNull: false,
});

export default i18n;
