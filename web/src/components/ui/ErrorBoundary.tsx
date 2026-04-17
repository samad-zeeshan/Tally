// Last line of defence for a rendering crash: the header and theme survive, the broken region shows a
// plain apology with a reload, and the error goes to the console for the person debugging.
import { Component, type ReactNode } from "react";
import { Button } from "./Button";

interface ErrorBoundaryProps {
  children: ReactNode;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: unknown) {
    console.error("ui crashed", error);
  }

  render() {
    if (!this.state.failed) {
      return this.props.children;
    }
    return (
      <div className="card">
        <h2>Something broke in the interface</h2>
        <p className="muted">The books themselves are fine; this is a display error. Reloading usually clears it.</p>
        <Button variant="secondary" onClick={() => window.location.reload()}>
          Reload
        </Button>
      </div>
    );
  }
}
