INSERT INTO product(id, code) VALUES (1, 'COMIC_BOOKS');
INSERT INTO product(id, code) VALUES (2, 'BOOKS');
INSERT INTO product(id, code) VALUES (3, 'MOVIES');
INSERT INTO product(id, code) VALUES (4, 'MUSIC');

SELECT setval(pg_get_serial_sequence('product', 'id'), (SELECT MAX(id) FROM product));
