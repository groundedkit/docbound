export type AnswerPart = { kind: 'text'; text: string } | { kind: 'citation'; index: number }

const CITATION = /\[(\d+)\]/g

/**
 * Splits answer text into plain text and [n] citation markers. Markers whose number isn't in
 * {@code validIndexes} stay as plain text, so a model that cites a non-existent source can't create a
 * dead link.
 */
export function splitCitations(text: string, validIndexes: ReadonlySet<number>): AnswerPart[] {
  const parts: AnswerPart[] = []
  let last = 0
  for (const match of text.matchAll(CITATION)) {
    const index = Number(match[1])
    if (!validIndexes.has(index)) continue
    if (match.index > last) parts.push({ kind: 'text', text: text.slice(last, match.index) })
    parts.push({ kind: 'citation', index })
    last = match.index + match[0].length
  }
  if (last < text.length) parts.push({ kind: 'text', text: text.slice(last) })
  return parts
}

/** Removes [n] markers, e.g. from a "not found" reply that the model decorated with a citation. */
export function stripCitations(text: string): string {
  return text.replace(/\s*\[\d+\]/g, '')
}
