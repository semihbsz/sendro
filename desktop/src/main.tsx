import { createRoot } from "react-dom/client";
import App from "./App";
import { AppProvider } from "./store";
import { UpdatesProvider } from "./updates";

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

// UpdatesProvider sits inside AppProvider on purpose: it has to see the
// transfer queue to know when an install must wait (UPDATES.md §3).
createRoot(container).render(
  <AppProvider>
    <UpdatesProvider>
      <App />
    </UpdatesProvider>
  </AppProvider>,
);
