import { LANG_OPTIONS, LangCode, tr } from '../i18n';
import { AccentColor, ThemeMode } from '../types';

interface SettingsViewProps {
  lang: LangCode;
  theme: ThemeMode;
  accent: AccentColor;
  autoRefresh: boolean;
  onLangChange: (l: LangCode) => void;
  onThemeChange: (t: ThemeMode) => void;
  onAccentChange: (a: AccentColor) => void;
  onAutoRefreshChange: (v: boolean) => void;
}

const ACCENTS: Array<{ id: AccentColor; label: string }> = [
  { id: 'blue', label: 'Blue' },
  { id: 'green', label: 'Green' },
  { id: 'violet', label: 'Violet' },
  { id: 'orange', label: 'Orange' },
  { id: 'rose', label: 'Rose' },
];

export function SettingsView({
  lang,
  theme,
  accent,
  autoRefresh,
  onLangChange,
  onThemeChange,
  onAccentChange,
  onAutoRefreshChange,
}: SettingsViewProps) {
  return (
    <div className="max-w-3xl space-y-5">
      <section className="rounded-xl border border-border bg-card p-6 space-y-5">
        <div>
          <h2 className="text-base font-semibold">{tr(lang, 'nav.settings')}</h2>
          <p className="text-xs text-muted-foreground mt-1">
            zh-CN / zh-TW / English / 日本語 / 한국어 · Light / Dark / System
          </p>
        </div>

        <div className="space-y-2">
          <div className="text-sm font-medium">{tr(lang, 'settings.lang')}</div>
          <div className="flex flex-wrap gap-2">
            {LANG_OPTIONS.map((opt) => (
              <button
                key={opt.code}
                type="button"
                onClick={() => onLangChange(opt.code)}
                className={`rounded-lg border px-3.5 py-2 text-xs font-medium transition-colors cursor-pointer ${
                  lang === opt.code
                    ? 'border-primary bg-primary text-primary-foreground'
                    : 'border-border bg-background hover:bg-muted/60'
                }`}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>

        <div className="space-y-2">
          <div className="text-sm font-medium">{tr(lang, 'settings.theme')}</div>
          <div className="grid grid-cols-3 gap-3">
            {(
              [
                { id: 'light', label: 'Light / 浅色' },
                { id: 'dark', label: 'Dark / 深色' },
                { id: 'system', label: 'System / 系统' },
              ] as const
            ).map((opt) => (
              <button
                key={opt.id}
                type="button"
                onClick={() => onThemeChange(opt.id)}
                className={`rounded-lg border p-3 text-left text-sm font-medium transition-colors cursor-pointer ${
                  theme === opt.id
                    ? 'border-primary ring-1 ring-primary bg-muted'
                    : 'border-border bg-background hover:bg-muted/60'
                }`}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>

        <div className="space-y-2">
          <div className="text-sm font-medium">{tr(lang, 'settings.accent')}</div>
          <div className="swatches">
            {ACCENTS.map((a) => (
              <button
                key={a.id}
                type="button"
                className={`swatch swatch-${a.id}`}
                aria-label={a.label}
                aria-pressed={accent === a.id}
                title={a.label}
                onClick={() => onAccentChange(a.id)}
              />
            ))}
          </div>
        </div>
      </section>

      <section className="rounded-xl border border-border bg-card p-6">
        <div className="flex items-center justify-between gap-4">
          <div>
            <div className="text-sm font-medium">{tr(lang, 'status.pollOn')}</div>
            <div className="text-xs text-muted-foreground mt-0.5">/api/devices (5s)</div>
          </div>
          <button
            type="button"
            onClick={() => onAutoRefreshChange(!autoRefresh)}
            className={`rounded-md border px-3 py-1.5 text-xs font-medium cursor-pointer ${
              autoRefresh
                ? 'border-primary bg-primary text-primary-foreground'
                : 'border-border bg-muted text-foreground'
            }`}
          >
            {autoRefresh ? tr(lang, 'status.pollOn') : tr(lang, 'status.pollOff')}
          </button>
        </div>
      </section>
    </div>
  );
}
