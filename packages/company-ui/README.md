# @company/ui 1.0.0

Approved UI components for source-code apps of the AI Software Factory. React 19, TypeScript, accessible by default, CSP-friendly
(no inline scripts, no external resources). Import the styles once: `import "@company/ui/styles.css";`

| Component | Key props | Accessibility |
| --- | --- | --- |
| `Layout` | `header`, `sidebar`, `padding: Space` | landmarks `header`/`aside`/`main` |
| `Stack` | `gap`, `padding: Space`, `direction`, `align`, `wrap`, `hidden` | — |
| `Navbar` / `Sidebar` | `brand`, `items: {label, href, current}` | `nav` with label, `aria-current` |
| `Button` | `tone`, `variant: solid/outline/ghost`, `size` | native button, visible focus |
| `Card` | `title`, `footer`, `padding`, `hidden` | `section` labelled by title |
| `Input` / `Select` / `Checkbox` | `label` (required), `hint`, `error` | label/`for`, `aria-invalid`, `aria-describedby`, error `role=alert` |
| `Form` | `gap` | native validation |
| `Table` | `caption` (required), `columns`, `rows`, `rowKey` | caption, `scope=col` |
| `Tabs` | `label`, `tabs: {id, title, content}` | WAI-ARIA tabs, arrow keys |
| `Dialog` / `Modal` | `open`, `title`, `onClose` (+ `onConfirm`) | `role=dialog`, focus trap, Escape, focus return |
| `ToastProvider` / `useToast` | `duration` | `aria-live=polite` |
| `Heading` / `Text` | `level`, `tone`, `hidden` | — |

Design tokens (`spacing`, `tones`) are the only values Design mode offers. Example:
```tsx
import { Layout, Navbar, Card, Button, Stack } from "@company/ui";
export function App() {
  return <Layout header={<Navbar brand="Kho" items={[{ label: "Tổng quan", href: "#", current: true }]}/>}>
    <Card title="Xin chào"><Stack gap="sm"><Button>Bắt đầu</Button></Stack></Card>
  </Layout>;
}
```
