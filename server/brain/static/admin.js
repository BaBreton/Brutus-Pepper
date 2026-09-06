
const $ = id => document.getElementById(id);
let token = sessionStorage.getItem('pepperAdminToken') || '';
let catalogues = { llm: [], stt: [], image_search: [] };

function say(id, text, kind = 'ok') {
  const node = $(id);
  node.textContent = text;
  node.className = 'status ' + kind;
}

async function api(path, options = {}, admin = true) {
  const headers = Object.assign({}, options.headers || {});
  if (admin) headers['Authorization'] = 'Bearer ' + token;
  const response = await fetch(path, Object.assign({}, options, { headers }));
  if (!response.ok) {
    const detail = await response.json().catch(() => ({}));
    throw new Error(typeof detail.detail === 'string' ? detail.detail : ('Vérifiez les champs saisis (HTTP ' + response.status + ').'));
  }
  return response.status === 204 ? null : response.json();
}

async function health() {
  try {
    await api('/api/health', {}, false);
    say('health', 'Serveur joignable');
  } catch (error) {
    say('health', 'Serveur injoignable : ' + error.message, 'error');
  }
}

// ── Connecteurs ────────────────────────────────────────────────────────────
// Doit rester aligné sur SECRET_KEYS de brain/settings.py : ces champs sont masqués
// à l'affichage et ne sont jamais renvoyés par l'API.
const SECRET_FIELDS = ['api_key', 'secret_key', 'access_key', 'token'];

// Un champ de clé laissé vide préserve la clé déjà enregistrée : c'est ce qui
// permet de changer de modèle sans avoir à ressaisir le secret.
function renderCredentials(kind, providerId, stored) {
  const container = $(kind + 'Creds');
  container.innerHTML = '';
  const entry = catalogues[kind].find(item => item.id === providerId);
  if (!entry) return;

  const fields = Array.isArray(entry.credential_fields)
    ? entry.credential_fields
    : [];
  if (!fields.length) {
    renderPricing(kind);
    return;
  }

  const saved = (stored && stored[providerId]) || {};
  const box = document.createElement('div');
  box.className = 'creds';
  const configured = entry.configured;
  box.innerHTML = `<h3>Identifiants ${entry.label}
    <span class="badge ${configured ? 'on' : ''}">${configured ? 'configuré' : 'à configurer'}</span></h3>`;

  for (const definition of fields) {
    const field = typeof definition === 'string' ? definition : definition.id;
    const value = saved[field];
    // Le type se décide sur le NOM du champ, pas sur la forme de la valeur reçue :
    // avant la première sauvegarde il n'y a pas de valeur, et une clé saisie en
    // clair à l'écran serait lisible par-dessus l'épaule de l'installateur.
    const isSecret = typeof definition === 'object'
      ? !!definition.secret : SECRET_FIELDS.includes(field);
    const label = typeof definition === 'object' && definition.label
      ? definition.label : field;
    const input = document.createElement('input');
    input.id = `${kind}-${providerId}-${field}`;
    input.autocomplete = 'off';
    if (isSecret) {
      input.type = 'password';
      input.placeholder = (value && value.configured)
        ? `déjà enregistrée (${value.masked}) — laisser vide pour la conserver`
        : ((typeof definition === 'object' && definition.placeholder) || 'non renseignée');
    } else {
      input.type = 'text';
      input.value = typeof value === 'string' ? value : '';
      if (typeof definition === 'object' && definition.placeholder) {
        input.placeholder = definition.placeholder;
      }
    }
    const tag = document.createElement('label');
    tag.textContent = label;
    tag.setAttribute('for', input.id);
    box.append(tag, input);
  }
  container.append(box);
  renderPricing(kind);
}

function fillProviders(kind, available, settings) {
  catalogues[kind] = available;
  if (kind === 'image_search' && $('imageContact')) {
    $('imageContact').value = settings.contact || '';
  }
  const provider = $(kind + 'Provider');
  provider.innerHTML = '';
  for (const entry of available) {
    const option = document.createElement('option');
    option.value = entry.id;
    option.textContent = entry.label + (entry.configured ? '' : ' — à configurer');
    provider.append(option);
  }
  const selected = kind === 'image_search' ? settings.provider : settings.active;
  const recommended = kind === 'image_search'
    ? (available.find(item => item.id === 'serper')?.id
      || available.find(item => item.id === 'brave')?.id
      || available[0]?.id)
    : available[0]?.id;
  provider.value = selected || recommended || '';
  if (kind === 'image_search' && $('imageContactFields')) {
    $('imageContactFields').hidden = provider.value !== 'wikimedia';
  }
  fillModels(kind, settings.model);
  renderCredentials(kind, provider.value, settings.credentials);
  provider.onchange = () => {
    fillModels(kind, null);
    renderCredentials(kind, provider.value, settings.credentials);
    if (kind === 'image_search' && $('imageContactFields')) {
      $('imageContactFields').hidden = provider.value !== 'wikimedia';
    }
    renderPricing(kind);
  };
}

function fillModels(kind, selected) {
  const entry = catalogues[kind].find(item => item.id === $(kind + 'Provider').value);
  const models = $(kind + 'Model');
  if (!models) return;
  models.innerHTML = '';
  for (const model of (entry && entry.models ? entry.models : [])) {
    const option = document.createElement('option');
    option.value = model.id;
    option.textContent = model.label;
    models.append(option);
  }
  if (selected) models.value = selected;
  renderPricing(kind);
}

function money(value) {
  return '$' + Number(value).toFixed(value < 0.01 ? 4 : 3);
}

function renderPricing(kind) {
  const node = $(kind + 'Pricing');
  if (!node) return;
  const entry = (catalogues[kind] || []).find(item => item.id === $(kind + 'Provider')?.value);
  const modelId = $(kind + 'Model')?.value;
  const model = entry?.models?.find(item => item.id === modelId);
  const pricing = model?.pricing || entry?.pricing;
  node.replaceChildren();
  if (!pricing) return;

  const title = document.createElement('strong');
  title.textContent = 'Coût indicatif';
  const line = document.createElement('span');
  if (pricing.usd_per_minute != null) {
    line.textContent = money(pricing.usd_per_minute) + ' / minute';
    if (kind === 'stt') {
      line.textContent += ' · 10 secondes ≈ ' + money(pricing.usd_per_minute / 6);
    }
  } else if (pricing.input_usd_per_million != null || pricing.output_usd_per_million != null) {
    const parts = [];
    if (pricing.input_usd_per_million != null) parts.push(money(pricing.input_usd_per_million) + ' / 1M entrée');
    if (pricing.output_usd_per_million != null) parts.push(money(pricing.output_usd_per_million) + ' / 1M sortie');
    line.textContent = parts.join(' · ');
    // Les coûts de conversation sont réellement lisibles pour un exemple court.
    // Pour les STT au token, le nombre de tokens audio dépend du fournisseur et
    // ne doit pas être transformé en fausse équivalence minute.
    if (kind === 'llm' && pricing.input_usd_per_million != null && pricing.output_usd_per_million != null) {
      const example = pricing.input_usd_per_million * 1000 / 1000000
        + pricing.output_usd_per_million * 250 / 1000000;
      line.textContent += ' · 1 000 entrée + 250 sortie ≈ ' + money(example);
    }
  } else if (pricing.unit === 'free') {
    line.textContent = 'Pas de coût d’API';
  } else {
    line.textContent = 'Tarif dépendant du plan fournisseur';
  }
  node.append(title, line);
  if (pricing.note) {
    const note = document.createElement('small');
    note.textContent = pricing.note;
    node.append(note);
  }
  if (pricing.source_url && /^https?:\/\//i.test(pricing.source_url)) {
    const link = document.createElement('a');
    link.href = pricing.source_url;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.textContent = 'Voir le tarif actuel ↗';
    node.append(link);
  }
}

async function saveConnector(kind) {
  const providerId = $(kind + 'Provider').value;
  const credentials = {};
  for (const input of $(kind + 'Creds').querySelectorAll('input')) {
    const field = input.id.split('-').slice(2).join('-');
    // On n'envoie que ce qui a été saisi : un champ vide conserve la valeur stockée.
    if (input.value !== '') credentials[field] = input.value;
  }
  const section = kind === 'image_search'
    ? { provider: providerId, contact: $('imageContact').value.trim() }
    : { active: providerId, model: $(kind + 'Model')?.value || '' };
  if (Object.keys(credentials).length) section.credentials = { [providerId]: credentials };
  try {
    const body = await api('/api/admin/connectors', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ [kind]: section }),
    });
    say(kind + 'Status', 'Enregistré');
    applySettings(body);
  } catch (error) {
    say(kind + 'Status', error.message, 'error');
  }
}

function applySettings(body) {
  fillProviders('llm', body.llm_available, body.settings.llm);
  fillProviders('stt', body.stt_available, body.settings.stt);
  fillProviders('image_search', body.image_search_available, body.settings.image_search);
}

// ── Hospitalité ────────────────────────────────────────────────────────────
async function loadHospitality() {
  const body = await api('/api/admin/hospitality');
  $('hospitalityActive').checked = !!body.active;
  $('company').value = body.company || '';
  $('visitors').value = (body.visitors || []).join('\n');
  $('notes').value = body.notes || '';
  $('mission').value = body.mission || '';
  renderPlaces(body.places || []);
  const visit = body.active_visit || {};
  $('visitCompany').value = visit.company || '';
  $('visitDomain').value = visit.domain || '';
  $('visitVisitors').value = (visit.visitors || []).join('\n');
  $('visitObjective').value = visit.objective || '';
  $('visitBrief').value = visit.brief || '';
  $('visitValidated').checked = !!visit.validated;
  visitSources = visit.sources || [];
  renderVisitSources();
  showGreeting(body);
}

// Une ligne vide en permanence : l'opérateur n'a pas à cliquer « Ajouter » pour saisir
// son premier lieu, et les lignes laissées vides sont ignorées à l'enregistrement.
function renderPlaces(places) {
  $('places').innerHTML = '';
  (places.length ? places : [{}]).forEach(addPlaceRow);
}

function addPlaceRow(place = {}) {
  const row = document.createElement('div');
  row.className = 'place';

  const name = document.createElement('input');
  name.setAttribute('aria-label', 'Nom du lieu');
  name.placeholder = 'ex. la cuisine';
  name.value = place.name || '';

  const directions = document.createElement('input');
  directions.setAttribute('aria-label', 'Indication à dire');
  directions.placeholder = 'ex. au fond du couloir';
  directions.value = place.directions || '';

  const pointDirection = document.createElement('select');
  pointDirection.className = 'place-point-direction';
  pointDirection.setAttribute('aria-label', 'Direction du pointage');
  [
    { value: '', label: 'Ne pas pointer' },
    { value: 'left', label: 'Pointer à gauche' },
    { value: 'right', label: 'Pointer à droite' },
  ].forEach(({ value, label }) => {
    const option = document.createElement('option');
    option.value = value;
    option.textContent = label;
    pointDirection.append(option);
  });
  pointDirection.value = place.point_direction || '';

  const remove = document.createElement('button');
  remove.className = 'danger';
  remove.type = 'button';
  remove.textContent = 'Retirer';
  remove.onclick = () => row.remove();

  row.append(name, directions, pointDirection, remove);
  $('places').append(row);
}

function readPlaces() {
  return [...$('places').querySelectorAll('.place')]
    .map(row => {
      const [name, directions] = row.querySelectorAll('input');
      const pointDirection = row.querySelector('select');
      return {
        name: name.value.trim(),
        directions: directions.value.trim(),
        point_direction: pointDirection?.value || '',
      };
    })
    .filter(place => place.name && place.directions);
}

function showGreeting(body) {
  const speech = (body.greeting || '').trim();
  $('greetingPreview').hidden = !(body.active && speech);
  $('greetingText').textContent = speech;
}

async function saveHospitality() {
  try {
    const body = await api('/api/admin/hospitality', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        active: $('hospitalityActive').checked,
        company: $('company').value.trim(),
        visitors: $('visitors').value.split('\n').map(v => v.trim()).filter(Boolean),
        notes: $('notes').value.trim(),
        mission: $('mission').value.trim(),
        places: readPlaces(),
        active_visit: readVisit(),
      }),
    });
    showGreeting(body);
    say('hospitalityStatus', body.active
      ? 'Accueil personnalisé enregistré pour les prochains échanges. L’accueil spontané se règle sur la tablette.'
      : 'Accueil personnalisé désactivé. Votre fiche est conservée.');
  } catch (error) {
    say('hospitalityStatus', error.message, 'error');
  }
}

// ── Appairage ──────────────────────────────────────────────────────────────
async function loadPairing() {
  $('pairingToken').textContent = (await api('/api/admin/pairing')).pairing_token;
}

async function rotatePairing() {
  if (!confirm('Régénérer le jeton ? Le robot devra être ré-appairé avec le nouveau.')) return;
  try {
    const body = await api('/api/admin/pairing', { method: 'POST' });
    $('pairingToken').textContent = body.pairing_token;
    say('pairingStatus', 'Nouveau jeton généré — saisissez-le sur la tablette', 'warn');
  } catch (error) {
    say('pairingStatus', error.message, 'error');
  }
}

// ── Médiathèque ────────────────────────────────────────────────────────────
const previewUrls = [];
let searchPreviewUrl = null;

async function testImageSearch() {
  const button = $('searchPreviewButton');
  const query = $('searchPreviewQuery').value.trim();
  if (!query) return say('searchPreviewStatus', 'Indiquez ce que vous voulez voir.', 'error');
  button.disabled = true;
  $('searchPreviewImage').hidden = true;
  if (searchPreviewUrl) URL.revokeObjectURL(searchPreviewUrl);
  searchPreviewUrl = null;
  say('searchPreviewStatus', 'Recherche et vérification du téléchargement…');
  const sessionToken = token;
  try {
    const result = await api('/api/admin/search/preview', {method: 'POST',
      headers: {'Content-Type': 'application/json'}, body: JSON.stringify({query})});
    const response = await fetch(result.url, {headers: {Authorization: 'Bearer ' + sessionToken}});
    if (!response.ok) throw new Error('Image trouvée mais aperçu indisponible.');
    const blob = await response.blob();
    if (token !== sessionToken) return;
    searchPreviewUrl = URL.createObjectURL(blob);
    $('searchPreviewImage').src = searchPreviewUrl;
    $('searchPreviewImage').alt = result.title;
    $('searchPreviewImage').hidden = false;
    say('searchPreviewStatus', result.title + ' · ' + result.source);
  } catch (error) {
    if (token === sessionToken) say('searchPreviewStatus', error.message, 'error');
  } finally { button.disabled = false; }
}

$('searchPreviewButton').onclick = testImageSearch;

async function previewSource(mediaId) {
  try {
    const response = await fetch('/api/admin/media/' + mediaId + '/content',
                                 { headers: { 'Authorization': 'Bearer ' + token } });
    if (!response.ok) return null;
    const url = URL.createObjectURL(await response.blob());
    previewUrls.push(url);
    return url;
  } catch (error) {
    return null;
  }
}

function releasePreviews() {
  while (previewUrls.length) URL.revokeObjectURL(previewUrls.pop());
}

async function loadMedia() {
  releasePreviews();
  const list = $('mediaList');
  try {
    const { items } = await api('/api/admin/media');
    list.innerHTML = '';
    if (!items.length) {
      list.innerHTML = '<div class="hint" style="margin-top:16px">Aucun média pour l\'instant.</div>';
      return;
    }
    for (const item of items) {
      const row = document.createElement('div');
      row.className = 'media';
      const preview = document.createElement(item.kind === 'image' ? 'img' : 'video');
      if (item.kind !== 'image') { preview.preload = 'metadata'; preview.muted = true; }
      // Un <img src> ne peut pas porter d'en-tête d'autorisation, et mettre le jeton
      // dans l'URL le ferait fuiter dans les journaux : on récupère en blob.
      previewSource(item.id).then(url => { if (url) preview.src = url; });
      const caption = document.createElement('div');
      caption.innerHTML = `<strong></strong>
        <small>${item.kind} · ${(item.size / 1048576).toFixed(1)} Mo</small>`;
      caption.querySelector('strong').textContent = item.name;
      row.append(preview, caption);
      const remove = document.createElement('button');
      remove.className = 'danger';
      remove.textContent = 'Supprimer';
      remove.onclick = async () => {
        if (!confirm(`Supprimer « ${item.name} » ?`)) return;
        try { await api('/api/admin/media/' + item.id, { method: 'DELETE' }); loadMedia(); }
        catch (error) { say('uploadStatus', error.message, 'error'); }
      };
      row.append(remove);
      list.append(row);
    }
  } catch (error) {
    list.textContent = error.message;
  }
}

async function upload() {
  const file = $('mediaFile').files[0];
  const name = $('mediaName').value.trim();
  if (!file || !name) return say('uploadStatus', 'Un nom et un fichier sont requis', 'error');
  try {
    say('uploadStatus', 'Envoi en cours…', 'ok');
    await api('/api/admin/media?name=' + encodeURIComponent(name),
              { method: 'PUT', headers: { 'Content-Type': file.type }, body: file });
    $('mediaName').value = ''; $('mediaFile').value = '';
    say('uploadStatus', 'Média ajouté');
    loadMedia();
  } catch (error) {
    say('uploadStatus', error.message, 'error');
  }
}

// ── Session ────────────────────────────────────────────────────────────────
const CARDS = ['llmCard', 'sttCard', 'searchCard', 'hospitalityCard',
               'pairingCard', 'mediaCard'];

async function enter() {
  const body = await api('/api/admin/connectors');
  applySettings(body);
  await Promise.all([loadHospitality(), loadPairing(), loadMedia()]);
  $('loginCard').hidden = true;
  $('logout').hidden = false;
  // Déconnecté, une seule carte est à l'écran : la grille se resserre autour d'elle
  // plutôt que de la laisser flotter seule dans une colonne.
  document.body.classList.remove('locked');
  $('navigation').hidden = false;
  showPage('visit');
}

async function login() {
  token = $('token').value.trim();
  if (!token) return say('loginStatus', 'Jeton requis', 'error');
  try {
    await enter();
    sessionStorage.setItem('pepperAdminToken', token);
    $('token').value = '';
    say('loginStatus', '');
  } catch (error) {
    say('loginStatus', error.message, 'error');
  }
}

function logout() {
  sessionStorage.removeItem('pepperAdminToken');
  token = '';
  if (searchPreviewUrl) URL.revokeObjectURL(searchPreviewUrl);
  searchPreviewUrl = null;
  $('searchPreviewImage').hidden = true;
  releasePreviews();
  $('navigation').hidden = true;
  $('pageIntro').hidden = true;
  $('pairingToken').textContent = '';
  $('token').value = '';
  $('loginCard').hidden = false;
  $('logout').hidden = true;
  document.body.classList.add('locked');
  CARDS.forEach(id => { $(id).hidden = true; });
}

$('login').onclick = login;
$('logout').onclick = logout;
$('token').onkeydown = event => { if (event.key === 'Enter') login(); };
$('saveLlm').onclick = () => saveConnector('llm');
$('saveStt').onclick = () => saveConnector('stt');
$('saveImageSearch').onclick = () => saveConnector('image_search');
$('saveHospitality').onclick = saveHospitality;
$('addPlace').onclick = () => addPlaceRow();
$('rotatePairing').onclick = rotatePairing;
$('upload').onclick = upload;


let visitSources = [];
const pages = {
  visit: ['hospitalityCard'], intelligence: ['llmCard', 'sttCard', 'searchCard'],
  media: ['mediaCard'], pairing: ['pairingCard']
};
const pageTitles = {
  visit: ['Préparer une visite', 'Renseignez les visiteurs attendus et les informations utiles à leur accueil.'],
  intelligence: ['Voix et intelligence', 'Choisissez les services de conversation, de transcription et de recherche.'],
  media: ['Images et vidéos', 'Ajoutez les médias que Pepper pourra afficher pendant une conversation.'],
  pairing: ['Connecter Pepper', 'Saisissez l’adresse de l’antenne et le jeton d’appairage sur la tablette.']
};
function showPage(page) {
  CARDS.forEach(id => { $(id).hidden = !pages[page].includes(id); });
  $('pageIntro').hidden = false;
  $('pageTitle').textContent = pageTitles[page][0];
  $('pageSubtitle').textContent = pageTitles[page][1];
  document.querySelectorAll('[data-page]').forEach(button => {
    button.setAttribute('aria-current', button.dataset.page === page ? 'page' : 'false');
  });
}
function readVisit() {
  const company = $('visitCompany').value.trim();
  if (!company && !$('visitBrief').value.trim() && !$('visitDomain').value.trim()) return null;
  return {company, domain: $('visitDomain').value.trim(),
    visitors: $('visitVisitors').value.split('\n').map(v => v.trim()).filter(Boolean),
    objective: $('visitObjective').value.trim(), brief: $('visitBrief').value.trim(),
    sources: visitSources, validated: $('visitValidated').checked};
}
function renderVisitSources() {
  $('visitSources').replaceChildren();
  visitSources.forEach((url, i) => {
    if (!/^https?:\/\//i.test(url)) return;
    const link = document.createElement('a');
    link.href = url; link.target = '_blank'; link.rel = 'noopener noreferrer';
    link.textContent = (i + 1) + '. ' + new URL(url).hostname;
    $('visitSources').append(link);
  });
}
async function researchVisit() {
  const button = $('researchVisit');
  if (!$('visitCompany').value.trim()) return say('researchStatus', 'Indiquez l’entreprise invitée.', 'error');
  if ($('visitBrief').value.trim() && !confirm('Remplacer le brouillon actuel par une nouvelle recherche ?')) return;
  button.disabled = true;
  say('researchStatus', 'Recherche des informations publiques…');
  try {
    const company = $('visitCompany').value.trim(), domain = $('visitDomain').value.trim();
    const body = await api('/api/admin/hospitality/research', {method:'POST',
      headers:{'Content-Type':'application/json'}, body:JSON.stringify({company, domain, use_llm:true})});
    if (company !== $('visitCompany').value.trim() || domain !== $('visitDomain').value.trim()) {
      return say('researchStatus', 'La sélection a changé. Relancez la recherche.', 'warn');
    }
    $('visitBrief').value = body.draft || '';
    $('visitValidated').checked = false;
    visitSources = (body.sources || []).map(s => s.url);
    renderVisitSources();
    say('researchStatus', [...body.warnings, ...body.unknowns].join(' '), 'warn');
  } catch (error) { say('researchStatus', error.message, 'error'); }
  finally { button.disabled = false; }
}
$('researchVisit').onclick = researchVisit;
document.querySelectorAll('[data-page]').forEach(button => button.onclick = () => showPage(button.dataset.page));
['visitCompany', 'visitDomain', 'visitVisitors', 'visitObjective', 'visitBrief'].forEach(id => {
  $(id).addEventListener('input', () => { $('visitValidated').checked = false; });
});
$('previewVisit').onclick = async () => {
  try {
    const body = await api('/api/admin/hospitality/preview', {method:'POST',
      headers:{'Content-Type':'application/json'}, body:JSON.stringify({
        company:$('company').value.trim(), active_visit:readVisit(), visitors:$('visitors').value.split('\n').filter(Boolean)})});
    $('greetingPreview').hidden = false; $('greetingText').textContent = body.greeting;
  } catch(error) { say('hospitalityStatus', error.message, 'error'); }
};

health();
document.body.classList.add('locked');
if (token) enter().catch(() => logout());
