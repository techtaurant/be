CREATE OR REPLACE FUNCTION pg_temp.restore_html_entities(value TEXT)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    restored TEXT := value;
BEGIN
    restored := replace(restored, '&quot;', '"');
    restored := replace(restored, '&#34;', '"');
    restored := replace(restored, '&#x22;', '"');
    restored := replace(restored, '&#X22;', '"');

    restored := replace(restored, '&apos;', '''');
    restored := replace(restored, '&#39;', '''');
    restored := replace(restored, '&#x27;', '''');
    restored := replace(restored, '&#X27;', '''');

    restored := replace(restored, '&nbsp;', CHR(160));
    restored := replace(restored, '&#160;', CHR(160));
    restored := replace(restored, '&#xA0;', CHR(160));
    restored := replace(restored, '&#Xa0;', CHR(160));

    restored := replace(restored, '&lt;', '<');
    restored := replace(restored, '&#60;', '<');
    restored := replace(restored, '&#x3C;', '<');
    restored := replace(restored, '&#x3c;', '<');

    restored := replace(restored, '&gt;', '>');
    restored := replace(restored, '&#62;', '>');
    restored := replace(restored, '&#x3E;', '>');
    restored := replace(restored, '&#x3e;', '>');

    restored := replace(restored, '&#38;', '&');
    restored := replace(restored, '&#x26;', '&');
    restored := replace(restored, '&#X26;', '&');
    restored := replace(restored, '&amp;', '&');

    RETURN restored;
END;
$$;

UPDATE posts
SET
    title = pg_temp.restore_html_entities(title),
    content = pg_temp.restore_html_entities(content)
WHERE
    title <> pg_temp.restore_html_entities(title)
    OR content <> pg_temp.restore_html_entities(content);

UPDATE comments
SET content = pg_temp.restore_html_entities(content)
WHERE content <> pg_temp.restore_html_entities(content);

DROP FUNCTION IF EXISTS pg_temp.restore_html_entities(TEXT);
