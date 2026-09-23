import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

/**
 * Renders a page inside a router, because every page here calls useNavigate.
 *
 * MemoryRouter rather than BrowserRouter: it keeps history in memory, so tests never
 * touch jsdom's URL and cannot leak a location into one another.
 */
export function renderPage(ui: ReactElement, { route = "/" }: { route?: string } = {}) {
  return render(<MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>);
}

/**
 * Same, but with somewhere to navigate to, so a redirect can be asserted on by looking
 * for the destination's marker text rather than by reaching into router internals.
 */
export function renderPageWithRoutes(
  ui: ReactElement,
  { route, path, destinations }: { route: string; path: string; destinations: Record<string, string> },
) {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <Routes>
        <Route path={path} element={ui} />
        {Object.entries(destinations).map(([to, marker]) => (
          <Route key={to} path={to} element={<div>{marker}</div>} />
        ))}
      </Routes>
    </MemoryRouter>,
  );
}
