-- A short summary of each document, written by the chat model after upload (see DocumentSummaries).
-- NULL until the summary has been generated. The same text is also stored in vector_store, tagged
-- kind = 'summary', so questions about a whole document can retrieve it.
ALTER TABLE documents ADD COLUMN summary TEXT;
