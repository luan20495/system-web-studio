-- Which model answered a prompt (e.g. "google/gemma-4-31b-it:free"); provider stays short ("mock", "openrouter").
ALTER TABLE prompt_runs ADD COLUMN model VARCHAR(160);
