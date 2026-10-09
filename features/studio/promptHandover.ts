/**
 * M-049 (R2-001): the prompt typed on Studio Home is handed to the new project's AI page IN MEMORY, never in the address.
 * Putting it in `?prompt=` meant that any link (`/studio/projects/<id>/ai?prompt=…`) made a signed-in editor's browser send an AI prompt on load (an action on load, creating a version),
 * and the text sat in history and access logs. Now only the person's own submit on Home can create a hand-over; it is consumed once; a reload (or any other way in) has nothing to send.
 */
const pending = new Map<string, string>();

/** remember the prompt the person just submitted for the project that was created for it */
export const handOverPrompt = (projectId: string, text: string): void => { pending.set(projectId, text); };

/** take (and forget) the prompt handed over for this project; null when there is none */
export const takeHandedOverPrompt = (projectId: string): string | null => { const t = pending.get(projectId) ?? null; pending.delete(projectId); return t; };
