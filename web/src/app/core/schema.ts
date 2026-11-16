import { SITE, url } from './site';

/**
 * The two entities that more than one page describes.
 *
 * Both were written for the localised pages and then wanted by the English
 * home page, which shows the same four questions and the same three films. A
 * second copy of either would be a second thing to keep true.
 */

export interface Answered {
  readonly q: string;
  readonly a: string;
}

/**
 * The questions on the page, marked up as questions.
 *
 * Worth the bytes because an answer engine quoting a product is going to quote
 * something; a page that states its own answers in a form meant to be lifted
 * gets to choose which sentences those are.
 */
export const faqPage = (path: string, locale: string, items: readonly Answered[]) => ({
  '@type': 'FAQPage',
  '@id': `${url(path)}#faq`,
  inLanguage: locale,
  mainEntity: items.map(({ q, a }) => ({
    '@type': 'Question',
    name: q,
    acceptedAnswer: { '@type': 'Answer', text: a },
  })),
});

export interface Film {
  readonly clip: string;
  readonly name: string;
  readonly description: string;
}

/**
 * The three films, declared as what they are.
 *
 * A VideoObject is one of the few kinds of structured data that changes the
 * shape of a result rather than decorating it — and these genuinely are a
 * spoken description of the product, in the language of whoever is reading.
 */
export const filmList = (path: string, slug: string, locale: string, films: readonly Film[]) => ({
  '@type': 'ItemList',
  '@id': `${url(path)}#films`,
  itemListElement: films.map((film, i) => ({
    '@type': 'ListItem',
    position: i + 1,
    item: {
      '@type': 'VideoObject',
      '@id': `${url(path)}#film-${film.clip}`,
      name: `${SITE.name} — ${film.name}`,
      description: film.description,
      inLanguage: locale,
      uploadDate: SITE.published.includes('T') ? SITE.published : `${SITE.published}T00:00:00Z`,
      thumbnailUrl: url(`/media/video/iphone/${slug}/${film.clip}.jpg`),
      contentUrl: url(`/media/video/iphone/${slug}/${film.clip}.mp4`),
      isFamilyFriendly: true,
      about: { '@id': url('/#app') },
    },
  })),
});

export interface StoryArticleInput {
  readonly headline: string;
  readonly lede: string;
  readonly body: string;
  readonly date: string;
  readonly event: { readonly name: string; readonly location: string | null };
  readonly white: { readonly name: string };
  readonly black: { readonly name: string };
  readonly premium?: {
    readonly subtitle: string;
    readonly readingMinutes: number;
    readonly sections: readonly {
      readonly heading: string;
      readonly body: string;
      readonly takeaways: readonly string[];
    }[];
  } | null;
}

/**
 * Rich NewsArticle / Article JSON-LD for tournament game reports & Premium Articles.
 */
export const storyArticle = (path: string, localeTag: string, story: StoryArticleInput) => {
  const isoDate = story.date.includes('T') ? story.date : `${story.date}T00:00:00Z`;
  const premiumParts = (story.premium?.sections ?? []).flatMap((sec) => [
    sec.heading,
    sec.body,
    ...sec.takeaways,
  ]);
  const fullBody = [story.body, ...premiumParts].filter(Boolean).join('\n\n');
  const wordCount = fullBody.split(/\s+/).filter(Boolean).length;

  return {
    '@type': story.premium ? ['NewsArticle', 'Article'] : 'NewsArticle',
    '@id': `${url(path)}#article`,
    headline: story.headline,
    ...(story.premium?.subtitle ? { alternativeHeadline: story.premium.subtitle } : {}),
    description: story.lede,
    articleBody: fullBody,
    wordCount,
    ...(story.premium?.sections?.length
      ? { articleSection: story.premium.sections.map((s) => s.heading) }
      : {}),
    ...(story.premium?.readingMinutes ? { timeRequired: `PT${story.premium.readingMinutes}M` } : {}),
    isAccessibleForFree: true,
    datePublished: isoDate,
    dateModified: isoDate,
    mainEntityOfPage: { '@id': `${url(path)}#webpage` },
    author: { '@id': url('/#organization') },
    publisher: { '@id': url('/#organization') },
    image: url('/og.jpg'),
    inLanguage: localeTag,
    about: { '@type': 'Thing', name: story.event.name },
    ...(story.event.location
      ? { contentLocation: { '@type': 'Place', name: story.event.location } }
      : {}),
    mentions: [story.white, story.black].map((p) => ({ '@type': 'Person', name: p.name })),
  };
};
