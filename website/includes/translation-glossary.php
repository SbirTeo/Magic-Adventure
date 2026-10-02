<?php
// Traduzioni scritte a mano per le etichette brevi del sito, che valgono SEMPRE prima di quelle
// di MyMemory (translate_batch in translate.php). MyMemory traduce male le parole isolate, perche'
// non ha il contesto: "Potenza" era tornata "Powe r", "Membro" "Memberium", "Banca" "Bank/Bank"
// nella scheda delle fazioni delle classifiche. Per una parola nuova basta aggiungere una riga:
// la chiave e' il testo italiano ESATTO della pagina (senza spazi intorno).

const TRANSLATION_GLOSSARY = [
    // Scheda della fazione (classifiche): dati
    'Territori' => ['en' => 'Territories', 'es' => 'Territorios', 'de' => 'Gebiete'],
    'Potenza'   => ['en' => 'Power', 'es' => 'Poder', 'de' => 'Macht'],
    'Banca'     => ['en' => 'Bank', 'es' => 'Banco', 'de' => 'Bank'],
    'Alleati'   => ['en' => 'Allies', 'es' => 'Aliados', 'de' => 'Verbündete'],
    'nessuno'   => ['en' => 'none', 'es' => 'ninguno', 'de' => 'keine'],
    'Membri'    => ['en' => 'Members', 'es' => 'Miembros', 'de' => 'Mitglieder'],
    // Gradi di fazione
    'Leader'    => ['en' => 'Leader', 'es' => 'Líder', 'de' => 'Anführer'],
    'Ufficiale' => ['en' => 'Officer', 'es' => 'Oficial', 'de' => 'Offizier'],
    'Membro'    => ['en' => 'Member', 'es' => 'Miembro', 'de' => 'Mitglied'],
    'Recluta'   => ['en' => 'Recruit', 'es' => 'Recluta', 'de' => 'Rekrut'],
    // Guida, guida per lo staff e regolamento: etichette brevi che MyMemory sbagliava
    // ("Chiedi alla guida" era diventato "Ask the driver", "Chiedi" "Asking").
    'Chiedi alla guida' => ['en' => 'Ask the guide', 'es' => 'Pregunta a la guía', 'de' => 'Frag die Anleitung'],
    'Chiedi'            => ['en' => 'Ask', 'es' => 'Preguntar', 'de' => 'Fragen'],
    'Cerca nella guida' => ['en' => 'Search the guide', 'es' => 'Buscar en la guía', 'de' => 'In der Anleitung suchen'],
    'Cerca'             => ['en' => 'Search', 'es' => 'Buscar', 'de' => 'Suchen'],
    'Guida'             => ['en' => 'Guide', 'es' => 'Guía', 'de' => 'Anleitung'],
    'Guida del server'  => ['en' => 'Server guide', 'es' => 'Guía del servidor', 'de' => 'Server-Anleitung'],
    'Regolamento'       => ['en' => 'Rules', 'es' => 'Reglamento', 'de' => 'Regeln'],
    'Indice'            => ['en' => 'Contents', 'es' => 'Índice', 'de' => 'Inhalt'],
    '↑ Indice'          => ['en' => '↑ Contents', 'es' => '↑ Índice', 'de' => '↑ Inhalt'],
    'In questo capitolo:' => ['en' => 'In this chapter:', 'es' => 'En este capítulo:', 'de' => 'In diesem Kapitel:'],
    'Nuovo sul server? Inizia da qui' => [
        'en' => 'New to the server? Start here',
        'es' => '¿Nuevo en el servidor? Empieza aquí',
        'de' => 'Neu auf dem Server? Fang hier an',
    ],
    // Stato della fazione
    'Non ha ancora un territorio.' => [
        'en' => 'It has no territory yet.',
        'es' => 'Todavía no tiene ningún territorio.',
        'de' => 'Sie hat noch kein Gebiet.',
    ],
    'La fazione è forte. Non puoi conquistarla.' => [
        'en' => 'The faction is strong. You can\'t conquer it.',
        'es' => 'La facción es fuerte. No puedes conquistarla.',
        'de' => 'Die Fraktion ist stark. Du kannst sie nicht erobern.',
    ],
    'La fazione è raidabile: la potenza è sotto ai territori.' => [
        'en' => 'The faction can be raided: its power is below its territories.',
        'es' => 'La facción se puede raidear: su poder está por debajo de sus territorios.',
        'de' => 'Die Fraktion kann geraidet werden: ihre Macht liegt unter ihren Gebieten.',
    ],
];
