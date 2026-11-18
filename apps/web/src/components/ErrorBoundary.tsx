import { Component, ErrorInfo, ReactNode } from 'react';
import { Button } from './ui/button';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('ToolHub Fleet UI ErrorBoundary:', error, info);
  }

  render() {
    if (this.state.error) {
      return (
        <main className="flex min-h-dvh items-center justify-center bg-canvas p-6">
          <div className="max-w-md space-y-4 rounded-xl border border-destructive/40 bg-background p-6 shadow-lg">
            <h1 className="text-base font-semibold text-destructive">界面渲染异常</h1>
            <p className="mono text-xs text-muted-foreground break-all">
              {this.state.error.message}
            </p>
            <div className="flex gap-2">
              <Button size="sm" onClick={() => this.setState({ error: null })}>
                重试恢复
              </Button>
              <Button variant="outline" size="sm" onClick={() => window.location.reload()}>
                刷新页面
              </Button>
            </div>
          </div>
        </main>
      );
    }
    return this.props.children;
  }
}
