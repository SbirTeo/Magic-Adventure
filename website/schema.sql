CREATE DATABASE IF NOT EXISTS magicadventure_web CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'magicweb'@'localhost' IDENTIFIED BY 'CHANGE_ME_DB_PASSWORD';
GRANT ALL PRIVILEGES ON magicadventure_web.* TO 'magicweb'@'localhost';
FLUSH PRIVILEGES;

USE magicadventure_web;

CREATE TABLE IF NOT EXISTS users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    mc_uuid CHAR(36) NOT NULL UNIQUE,
    mc_username VARCHAR(32) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NULL,
    is_admin TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login DATETIME NULL,
    -- Ultima pagina aperta sul sito: alimenta il riquadro "Sul sito ora" nella home
    last_seen DATETIME NULL,
    KEY idx_last_seen (last_seen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- "Resta collegato": un cookie a lunga scadenza per browser. Nel database c'e' il selettore
-- in chiaro (per trovare la riga) e SOLO l'hash del validatore, come per le password.
CREATE TABLE IF NOT EXISTS remember_tokens (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    selector CHAR(32) NOT NULL UNIQUE,
    validator_hash CHAR(64) NOT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at DATETIME NULL,
    KEY idx_scadenza (expires_at),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS link_codes (
    code VARCHAR(8) PRIMARY KEY,
    mc_uuid CHAR(36) NOT NULL,
    mc_username VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NOT NULL,
    used TINYINT(1) NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Grado di permessi in gioco (LuckPerms) di ogni giocatore: la scrive il plugin MagixWeb
-- (a ogni join e a intervalli), il sito la legge per mostrare il tag col colore del prefisso.
-- Il COLLATE esplicito serve a poterla joinare con users.mc_uuid.
CREATE TABLE IF NOT EXISTS mc_ranks (
    mc_uuid CHAR(36) NOT NULL PRIMARY KEY,
    mc_username VARCHAR(32) NOT NULL,
    group_name VARCHAR(64) NOT NULL,
    group_display VARCHAR(64) NOT NULL,
    tag_text VARCHAR(64) NULL,
    tag_color CHAR(7) NULL,
    tags_json TEXT NULL, -- tutti i gradi del prefisso impilato: [{"text":"Admin","color":"#FF5555"},...]
    name_color CHAR(7) NULL, -- colore del nome = grado col peso piu' alto, track o non track
    groups_json TEXT NULL, -- tutti i gruppi posseduti: ["admin","vip","default"], base dei permessi web
    weight INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Elenco dei gruppi del gioco, specchiato dal plugin MagixWeb (non si creano dal sito).
CREATE TABLE IF NOT EXISTS web_groups (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    display VARCHAR(64) NOT NULL,
    color CHAR(7) NULL,
    weight INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Cosa puo' fare sul sito ciascun gruppo del gioco (catalogo in includes/permissions.php).
CREATE TABLE IF NOT EXISTS web_group_permissions (
    group_name VARCHAR(64) NOT NULL,
    permission VARCHAR(64) NOT NULL,
    PRIMARY KEY (group_name, permission)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Store: categorie e pacchetti acquistabili (gestiti da /manage.php?section=store)
CREATE TABLE IF NOT EXISTS store_categories (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    slug VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255) NULL,
    -- Sconto valido per tutti i pacchetti della categoria che non ne hanno uno proprio
    discount_type ENUM('percentuale','importo') NULL,
    discount_value DECIMAL(8,2) NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS store_packages (
    id INT AUTO_INCREMENT PRIMARY KEY,
    category_id INT NULL,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(140) NOT NULL UNIQUE,
    image_url VARCHAR(500) NULL,
    description TEXT NULL,          -- una riga per voce: l'elenco "cosa ottieni" delle card
    long_description TEXT NULL,     -- testo esteso della pagina dedicata /pacchetto/<slug>
    price DECIMAL(8,2) NOT NULL DEFAULT 0,
    -- Sconto del singolo pacchetto: vince su quello della categoria e su quello generale
    discount_type ENUM('percentuale','importo') NULL,
    discount_value DECIMAL(8,2) NOT NULL DEFAULT 0,
    commands TEXT NULL, -- uno per riga, {player} = nome del giocatore
    sort_order INT NOT NULL DEFAULT 0,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    -- Pacchetto in evidenza: ne esiste UNO SOLO in tutto lo store (non uno per categoria).
    -- Il vincolo lo tiene il codice (store_pkg_save azzera gli altri dentro una transazione),
    -- perche' MySQL non ha indici unici parziali.
    featured TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL,
    FOREIGN KEY (category_id) REFERENCES store_categories(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS blog_posts (
    id INT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    -- Riga sotto il titolo (non dopo i due punti): vuota = non compare
    subtitle VARCHAR(255) NOT NULL DEFAULT '',
    slug VARCHAR(220) NOT NULL UNIQUE,
    cover_image VARCHAR(500) NULL,
    -- Punto da tenere al centro quando la copertina viene ritagliata (background-position)
    cover_position VARCHAR(20) NOT NULL DEFAULT '50% 50%',      -- telefono (fascia)
    cover_position_pc VARCHAR(20) NOT NULL DEFAULT '50% 50%',   -- computer (colonna)
    body MEDIUMTEXT NOT NULL,
    author_user_id INT NULL,
    published TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL,
    -- Cestino: quando un articolo si elimina non sparisce, si segna la data qui. Resta
    -- fuori dal sito ma recuperabile da /manage?section=blog_cestino.
    deleted_at DATETIME NULL DEFAULT NULL,
    KEY idx_cestino (deleted_at),
    FOREIGN KEY (author_user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS forum_categories (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    slug VARCHAR(120) NOT NULL UNIQUE,
    description VARCHAR(255) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    -- Tinta della categoria sul forum. NULL = automatica (la sceglie forum_tinta()
    -- dall'id, pescando dai colori del tema).
    color VARCHAR(7) NULL DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS forum_topics (
    id INT AUTO_INCREMENT PRIMARY KEY,
    category_id INT NOT NULL,
    user_id INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_post_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_locked TINYINT(1) NOT NULL DEFAULT 0,
    is_pinned TINYINT(1) NOT NULL DEFAULT 0,
    views INT NOT NULL DEFAULT 0,
    FOREIGN KEY (category_id) REFERENCES forum_categories(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS forum_posts (
    id INT AUTO_INCREMENT PRIMARY KEY,
    topic_id INT NOT NULL,
    user_id INT NOT NULL,
    body TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (topic_id) REFERENCES forum_topics(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Visite alle discussioni: una riga per (discussione, utente), quindi forum_topics.views
-- conta le PERSONE che l'hanno aperta, non i caricamenti di pagina.
CREATE TABLE IF NOT EXISTS forum_topic_views (
    topic_id INT NOT NULL,
    user_id INT NOT NULL,
    viewed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (topic_id, user_id),
    KEY idx_utente (user_id),
    FOREIGN KEY (topic_id) REFERENCES forum_topics(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- "Mi piace" ai messaggi: una riga per (messaggio, utente), quindi uno ciascuno.
CREATE TABLE IF NOT EXISTS forum_likes (
    post_id INT NOT NULL,
    user_id INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (post_id, user_id),
    KEY idx_utente (user_id),
    FOREIGN KEY (post_id) REFERENCES forum_posts(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO forum_categories (name, slug, description, sort_order) VALUES
    ('Annunci', 'annunci', 'Novità e comunicazioni ufficiali del server', 1),
    ('Discussioni generali', 'generale', 'Parla di tutto ciò che riguarda MAGICADVENTURE', 2),
    ('Fazioni', 'fazioni', 'Alleanze, guerre, territori e diplomazia', 3),
    ('Aiuto e supporto', 'supporto', 'Segnala bug o chiedi assistenza', 4);

-- Pagine statiche personalizzate (regolamento + pagine create da /manage.php?section=pages)
CREATE TABLE IF NOT EXISTS site_pages (
    slug VARCHAR(50) PRIMARY KEY,
    title VARCHAR(200) NOT NULL DEFAULT '',
    body MEDIUMTEXT NOT NULL,
    -- Colonna laterale su questa pagina (vedi pagina_con_sidebar)
    show_sidebar TINYINT(1) NOT NULL DEFAULT 1,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Impostazioni generali del sito (nome, logo, colori tema), gestibili da /manage.php?section=theme
CREATE TABLE IF NOT EXISTS site_settings (
    setting_key VARCHAR(50) PRIMARY KEY,
    setting_value TEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO site_settings (setting_key, setting_value) VALUES
    ('site_name', 'MAGICADVENTURE'),
    ('logo_url', '/assets/img/logo.png'),
    -- Icona della linguetta del browser e logo ridotto (barra in alto + anteprime social)
    ('favicon_url', ''),
    ('logo_small_url', ''),
    ('nav_logo_enabled', '1'),
    ('nav_logo_size', '44'),
    ('card_overlay_blog', '1'),
    ('card_overlay_store', '1'),
    ('meta_description', 'MAGICADVENTURE è un server Minecraft italiano con Fazioni e conquista territori. Forum, blog e regolamento su magicadventure.it — IP di gioco: mc.magicadventure.it'),
    ('meta_title_home', 'MAGICADVENTURE — Server Minecraft italiano con Fazioni'),
    ('og_image', ''),
    ('google_site_verification', ''),
    ('color_bg', '#0a0710'),
    ('color_purple', '#c04ff0'),
    ('color_green', '#a3e635'),
    ('vip_banner_enabled', '1'),
    ('vip_banner_icon', '👑'),
    ('vip_banner_tag', '★ Promozione VIP ★'),
    ('vip_banner_title', 'Sblocca il massimo del server'),
    ('vip_banner_text', 'Kit esclusivi, comandi speciali e tanti altri vantaggi per chi sostiene MAGICADVENTURE.'),
    ('vip_banner_button_text', 'Scopri di più'),
    ('vip_banner_button_url', '#'),
    ('vip_banner_color', '#f0c75e'),
    ('vip_banner_image', ''),
    ('vip_banner_overlay_intensity', '85'),
    ('vip_banner_border_anim', '1'),
    ('blog_per_page', '6'),
    ('featured_overlay_color', '#c04ff0'),
    ('featured_overlay_intensity', '35'),
    ('grid_overlay_color', '#9a9aa0'),
    ('grid_overlay_intensity', '18'),
    ('featured_border_anim', '1'),
    ('store_btn_border_anim', '1'),
    ('chat_enabled', '1'),
    ('chat_show_game', '1'),
    ('chat_history', '40'),
    ('chat_slowmode', '3'),
    -- Fin dove la chat e' stata svuotata l'ultima volta (id del messaggio piu' alto
    -- cancellato): serve alle pagine gia' aperte per togliere dallo schermo i
    -- messaggi che nel frattempo non esistono piu'.
    ('chat_purge_id', '0'),
    ('store_sidebar_enabled', '1'),
    ('store_sidebar_recent_count', '5'),
    ('store_sidebar_recent_title', 'Ultimi acquisti'),
    ('store_sidebar_show_amount', '1'),
    ('store_sidebar_show_package', '1'),
    ('store_sidebar_show_date', '1'),
    ('store_discount_type', 'percentuale'),
    ('store_discount_value', '0'),
    ('store_sidebar_show_name', '1'),
    ('store_sidebar_show_rank', '1'),
    ('store_sidebar_top_enabled', '1'),
    ('store_sidebar_top_title', 'Miglior sostenitore'),
    ('store_sidebar_top_days', '0'),
    ('store_sidebar_include_manual', '0');

-- Chat live della home, in ponte con la chat pubblica del server.
-- I messaggi con source='game' li scrive il plugin MagixWeb (mirror della chat di gioco);
-- quelli con source='web' li scrive il sito e il plugin li ripubblica in gioco quando
-- delivered = 0, poi li marca come consegnati.
-- COLLATE esplicito: mc_uuid va joinata con mc_ranks/users (vedi mc_ranks).
CREATE TABLE IF NOT EXISTS web_chat (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    source ENUM('web','game') NOT NULL DEFAULT 'web',
    mc_uuid CHAR(36) NULL,
    mc_username VARCHAR(32) NOT NULL,
    message VARCHAR(256) NOT NULL,
    delivered TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_consegna (delivered, source, id),
    KEY idx_data (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Voci della barra di navigazione, gestibili da /manage.php?section=nav
CREATE TABLE IF NOT EXISTS nav_items (
    id INT AUTO_INCREMENT PRIMARY KEY,
    label VARCHAR(100) NOT NULL,
    url VARCHAR(255) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    -- Colonna laterale su quella pagina e su quelle sotto di essa (vedi pagina_con_sidebar)
    show_sidebar TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Lo Store e' una voce come le altre: header.php la riconosce dall'URL e la disegna come pulsante oro.
INSERT IGNORE INTO nav_items (label, url, sort_order, enabled) VALUES
    ('Home', '/', 1, 1),
    ('Forum', '/forum/', 2, 1),
    ('Classifiche', '/classifiche.php', 3, 1),
    ('Regolamento', '/regolamento.php', 4, 1),
    ('Store', '/store.php', 5, 1);
