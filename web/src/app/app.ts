import { ChangeDetectionStrategy, Component, afterNextRender, inject } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs/operators';

import { APP_STORE_PT, storeHref, taggedCampaign } from './core/site';
import { SiteFooter } from './shared/site-footer/site-footer';
import { SiteHeader } from './shared/site-header/site-header';

const detectVisitSource = (): string => {
  try {
    const params = new URLSearchParams(window.location.search);
    const utm = (params.get('utm_source') || '').toLowerCase().trim();
    const refHost = document.referrer ? new URL(document.referrer).hostname.toLowerCase() : '';
    const raw = utm || refHost;
    if (raw.includes('chatgpt.com') || raw.includes('openai.com') || raw === 'chatgpt' || raw === 'openai') {
      return 'chatgpt';
    }
    if (raw.includes('perplexity.ai') || raw === 'perplexity') return 'perplexity';
    if (raw.includes('bing.com') || raw.includes('copilot.microsoft.com') || raw === 'bing' || raw === 'copilot') {
      return 'bing';
    }
    if (raw.includes('google.') || raw === 'google') return 'google';
    if (raw.includes('producthunt.com') || raw === 'producthunt' || raw === 'ph') return 'ph';
    if (raw.includes('alternativeto.net') || raw === 'alternativeto' || raw === 'altto') return 'altto';
    if (raw.includes('dev.to') || raw === 'devto') return 'devto';
    if (raw.includes('hashnode.') || raw === 'hashnode') return 'hashnode';
    if (raw.includes('reddit.com') || raw === 'reddit') return 'reddit';
    if (utm) return utm.replace(/[^a-z0-9]/g, '').slice(0, 10);
  } catch {
    // Ignore malformed referrer or storage errors.
  }
  return '';
};

@Component({
  selector: 'bp-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, SiteHeader, SiteFooter],
  template: `
    <bp-site-header />
    <main id="main"><router-outlet /></main>
    <bp-site-footer />
  `,
  styles: `
    :host {
      display: flex;
      min-height: 100svh;
      flex-direction: column;
    }

    main {
      flex: 1;
    }
  `,
})
export class App {
  private readonly router = inject(Router);

  constructor() {
    afterNextRender(() => {
      let src = detectVisitSource();
      try {
        if (src) {
          sessionStorage.setItem('visit-source', src);
        } else {
          src = sessionStorage.getItem('visit-source') || '';
        }
      } catch {
        // Ignore sessionStorage restrictions.
      }

      const applyTagToAnchor = (anchor: HTMLAnchorElement, campaign: string) => {
        try {
          const parsed = new URL(anchor.href, window.location.origin);
          parsed.searchParams.set('pt', APP_STORE_PT);
          parsed.searchParams.set('ct', campaign);
          parsed.searchParams.set('mt', '8');
          anchor.href = parsed.toString();
        } catch {
          anchor.href = storeHref(campaign);
        }
      };

      const rewriteAllStoreLinks = () => {
        let activeSrc = src;
        try {
          activeSrc = sessionStorage.getItem('visit-source') || src;
        } catch {}
        const ct = taggedCampaign(window.location.pathname, activeSrc);
        document
          .querySelectorAll<HTMLAnchorElement>('a[href*="id6803566012"]')
          .forEach((a) => applyTagToAnchor(a, ct));
      };

      rewriteAllStoreLinks();

      this.router.events
        .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
        .subscribe(() => {
          setTimeout(rewriteAllStoreLinks, 0);
        });

      const onStoreClick = (ev: MouseEvent) => {
        const target = ev.target as Element | null;
        const anchor = target?.closest?.('a[href*="id6803566012"]') as HTMLAnchorElement | null;
        if (!anchor) return;
        let activeSrc = src;
        try {
          activeSrc = sessionStorage.getItem('visit-source') || src;
        } catch {}
        const ct = taggedCampaign(window.location.pathname, activeSrc);
        applyTagToAnchor(anchor, ct);
        const w = window as unknown as { gtag?: (...args: unknown[]) => void };
        if (typeof w.gtag === 'function') {
          w.gtag('event', 'app_store_click', {
            campaign: ct,
            source: activeSrc || 'direct',
            page_path: window.location.pathname,
          });
        }
      };

      document.addEventListener('click', onStoreClick, { capture: true });
      document.addEventListener('auxclick', onStoreClick, { capture: true });
    });
  }
}
