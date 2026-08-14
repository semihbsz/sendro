import { createRoot } from "react-dom/client";
import App from "./App";
import { AppProvider } from "./store";

// Bundled fonts — the app must render identically offline (no Google Fonts).
import "@fontsource/instrument-sans/400.css";
import "@fontsource/instrument-sans/500.css";
import "@fontsource/instrument-sans/600.css";
import "@fontsource/instrument-sans/700.css";
import "@fontsource/jetbrains-mono/400.css";
import "@fontsource/jetbrains-mono/500.css";
import "@fontsource/jetbrains-mono/600.css";

import "./styles/tokens.css";
import "./styles/app.css";

const container = document.getElementById("root");
if (!container) {
  throw new Error("missing #root element");
}

createRoot(container).render(
  <AppProvider>
    <App />
  </AppProvider>,
);
