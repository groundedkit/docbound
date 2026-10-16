import { describe, expect, it } from 'vitest'
import { documentTitle, fileTag, parseBlocks, splitBold, tidyPassage } from './format'

describe('parseBlocks', () => {
  it('groups lines into paragraphs and lists', () => {
    expect(parseBlocks('Intro line\nsecond line\n\n- one\n- two\n\n1. first\n2) second\nAfter')).toEqual([
      { kind: 'paragraph', lines: ['Intro line', 'second line'] },
      { kind: 'bullets', items: ['one', 'two'] },
      { kind: 'numbered', items: ['first', 'second'] },
      { kind: 'paragraph', lines: ['After'] },
    ])
  })

  it('accepts * and • bullets and ignores repeated blank lines', () => {
    expect(parseBlocks('* a\n• b\n\n\n\nText')).toEqual([
      { kind: 'bullets', items: ['a', 'b'] },
      { kind: 'paragraph', lines: ['Text'] },
    ])
  })

  it('keeps text that only looks like markup as a paragraph', () => {
    expect(parseBlocks('-not a bullet\n2026 was a good year')).toEqual([
      { kind: 'paragraph', lines: ['-not a bullet', '2026 was a good year'] },
    ])
  })
})

describe('splitBold', () => {
  it('splits bold spans', () => {
    expect(splitBold('A **bold** word and **another**')).toEqual([
      { bold: false, text: 'A ' },
      { bold: true, text: 'bold' },
      { bold: false, text: ' word and ' },
      { bold: true, text: 'another' },
    ])
  })

  it('leaves an unclosed marker as text while streaming', () => {
    expect(splitBold('Still **streaming')).toEqual([{ bold: false, text: 'Still **streaming' }])
  })
})

describe('documentTitle', () => {
  it('turns a filename into a readable title like the backend does', () => {
    expect(documentTitle('policy_workplace_concerns.pdf')).toBe('Policy Workplace Concerns')
    expect(documentTitle('payments-API.md')).toBe('Payments API')
    expect(documentTitle('.pdf')).toBe('.pdf')
  })
})

describe('tidyPassage', () => {
  it('joins wrapped PDF lines into paragraphs and keeps list items separate', () => {
    const text = 'There are multiple ways\nto raise a concern:\n\n● Your manager, or\nanother manager;\n● Any HR Consultant ( HR)'
    expect(tidyPassage(text)).toEqual([
      'There are multiple ways to raise a concern:',
      '● Your manager, or another manager;',
      '● Any HR Consultant (HR)',
    ])
  })
})

describe('fileTag', () => {
  it('names the file type', () => {
    expect(fileTag('handbook.PDF')).toBe('PDF')
    expect(fileTag('notes.markdown')).toBe('MD')
    expect(fileTag('README')).toBe('FILE')
  })
})
