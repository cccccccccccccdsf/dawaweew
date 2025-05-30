const AUTO_CLEAR_INTERVAL = 3600000;
const TRANSACTION_HISTORY_KEY = 'transactionHistory';
const DEFAULT_THRESHOLDS = { view: 500, notify: 4000 };
const SUPPORTED_CHAINS = ['bsc', 'sol', 'tron', 'base', 'eth'];
const PRICE_UPDATE_INTERVAL = 5000; // Обновление каждые 5 секунд

let darkMode = localStorage.getItem('darkMode') === 'true';
let soundEnabled = localStorage.getItem('soundEnabled') !== 'false';
let clientThresholds = JSON.parse(localStorage.getItem('clientThresholds') || '{}');
let priceHistory = {};
let clearIntervals = {};
let lastPrices = {};
let transactionPriceChanges = {}; // Объект для хранения изменений цен по транзакциям

const elements = {
    themeToggle: document.getElementById('theme-toggle'),
    soundToggle: document.getElementById('sound-toggle'),
    addTokenBtn: document.getElementById('add-token-btn'),
    addTokenForm: document.getElementById('add-token-form'),
    addTokenOverlay: document.getElementById('add-token-overlay'),
    closeAddToken: document.getElementById('close-add-token'),
    submitToken: document.getElementById('submit-token'),
    gmgnUrl: document.getElementById('gmgn-url'),
    mexcName: document.getElementById('mexc-name'),
    displayName: document.getElementById('display-name'),
    symbolName: document.getElementById('symbol-name'),
    customThemesBtn: document.getElementById('custom-themes-btn'),
    customThemesOverlay: document.getElementById('custom-themes-overlay'),
    customThemesForm: document.getElementById('custom-themes-form'),
    closeCustomThemes: document.getElementById('close-custom-themes'),
    notificationSound: document.getElementById('notification-sound')
};

// Debugging: Log missing elements
Object.entries(elements).forEach(([key, el]) => {
    if (!el) console.error(`Элемент не найден: ${key}`);
});

const socket = new SockJS('/ws');
const stompClient = Stomp.over(socket);

stompClient.connect({}, () => {
    console.log('Подключено к WebSocket');
    stompClient.subscribe('/topic/price_update', (message) => {
        const data = JSON.parse(message.body);
        const token = data.token;
        if (!priceHistory[token]) priceHistory[token] = { lastPrice: null, mexcPrice: null };

        const formatPrice = (price) => {
            if (price === 0) return '0';
            if (price < 0.000001) {
                return price.toExponential(6).replace('e', 'e-');
            }
            return price.toLocaleString(undefined, { minimumFractionDigits: 6, maximumFractionDigits: 6 });
        };

        if (data.lastPrice > 0) {
            updatePrice(token, 'lastPrice', data.lastPrice, formatPrice);
        }
        if (data.mexcPrice > 0) {
            updatePrice(token, 'mexcPrice', data.mexcPrice, formatPrice);
        }
        updatePriceDiff(token);
        updateTransactionPriceChanges(token); // Обновляем изменения цен для транзакций
    });

    stompClient.subscribe('/topic/new_transaction', (message) => {
        console.log('Получена новая транзакция:', message.body);
        const data = JSON.parse(message.body);
        const token = data.token;
        const container = document.getElementById(`transactions-${token}`);
        if (!container) return;

        saveTransaction(token, data);
        const notifyThreshold = clientThresholds[token]?.notify || DEFAULT_THRESHOLDS.notify;
        const volumeMatch = data.text.match(/\$([\d\s,.]+)/);
        const volume = volumeMatch ? parseFloat(volumeMatch[1].replace(/[\s,]/g, '')) : 0;
        if (shouldDisplayTransaction(data, token) || volume >= notifyThreshold) {
            addTransactionToDOM(data, container);
        }
    });

    stompClient.subscribe('/topic/update_transaction', (message) => {
        const data = JSON.parse(message.body);
        const transactionElement = document.getElementById(`transaction-${data.id}`);
        if (transactionElement) {
            transactionElement.innerHTML = highlightBuySell(data.text);
            updateTransactionPriceChangeDisplay(data.id); // Обновляем отображение изменения цены
        }
    });

    stompClient.subscribe('/topic/clear_transactions', (message) => {
        const data = JSON.parse(message.body);
        clearTransactions(data.token);
    });

    activateTokens();
    startPriceChangeUpdates(); // Запускаем периодическое обновление изменений цен
}, (error) => {
    console.error('Ошибка подключения к WebSocket:', error);
});

document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('.remove-token').forEach(button => {
        button.addEventListener('click', async () => {
            const token = button.dataset.token;
            console.log('Нажата кнопка Удалить токен для:', token);
            try {
                const response = await fetch('/remove_token', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ token })
                });
                const result = await response.json();
                if (result.success) {
                    console.log('Токен успешно удален:', token);
                    const card = document.getElementById(`card-${token}`);
                    if (card) card.remove();
                } else {
                    console.error('Не удалось удалить токен:', result.error);
                    alert(`Ошибка удаления токена: ${result.error}`);
                }
            } catch (error) {
                console.error('Ошибка удаления токена:', error);
                alert('Не удалось удалить токен. Пожалуйста, попробуйте снова.');
            }
        });
    });

    document.querySelectorAll('.manual-clear').forEach(button => {
        button.addEventListener('click', () => {
            const token = button.dataset.token;
            console.log('Нажата кнопка Очистить транзакции для:', token);
            clearTransactions(token);
            stompClient.send('/app/clear_transactions', {}, JSON.stringify({ token }));
        });
    });

    document.querySelectorAll('.threshold-input').forEach(input => {
        input.addEventListener('change', async () => {
            const token = input.dataset.token;
            const type = input.dataset.type;
            const value = parseFloat(input.value) || DEFAULT_THRESHOLDS[type];
            if (!clientThresholds[token]) clientThresholds[token] = {};
            clientThresholds[token][type] = value;
            localStorage.setItem('clientThresholds', JSON.stringify(clientThresholds));
            console.log(`Обновлен порог ${type} для ${token}: ${value}`);

            // Синхронизация порогов с бэкендом
            if (type === 'notify' || type === 'view') {
                try {
                    const response = await fetch('/update_threshold', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ token: token, notifyThreshold: clientThresholds[token].notify || DEFAULT_THRESHOLDS.notify, viewThreshold: clientThresholds[token].view || DEFAULT_THRESHOLDS.view })
                    });
                    const result = await response.json();
                    if (result.success) {
                        console.log(`Пороги успешно синхронизированы для ${token}: notify=${clientThresholds[token].notify || DEFAULT_THRESHOLDS.notify}, view=${clientThresholds[token].view || DEFAULT_THRESHOLDS.view}, новый порог: ${result.threshold}`);
                    } else {
                        console.error('Не удалось синхронизировать пороги:', result.error);
                    }
                } catch (error) {
                    console.error('Ошибка синхронизации порогов:', error);
                }
            }
        });
        const token = input.dataset.token;
        const type = input.dataset.type;
        const storedThresholds = JSON.parse(localStorage.getItem('clientThresholds') || '{}');
        input.value = storedThresholds[token]?.[type] || DEFAULT_THRESHOLDS[type];
        if (!clientThresholds[token]) clientThresholds[token] = {};
        clientThresholds[token][type] = parseFloat(input.value) || DEFAULT_THRESHOLDS[type];
    });
});

const fetchTokenIconAndName = async (tokenSymbol) => {
    try {
        const response = await fetch(`/icon/${encodeURIComponent(tokenSymbol)}`);
        return response.ok ? await response.json() : null;
    } catch (error) {
        console.error(`Не удалось получить иконку токена для ${tokenSymbol}:`, error);
        return null;
    }
};

function toggleModal(overlay, modal, show) {
    if (!overlay || !modal) {
        console.error('Элементы модального окна не найдены:', { overlay, modal });
        return;
    }
    overlay.classList.toggle('visible', show);
    modal.classList.toggle('show', show);
}

if (elements.addTokenBtn) {
    elements.addTokenBtn.addEventListener('click', () => {
        console.log('Нажата кнопка Добавить токен');
        toggleModal(elements.addTokenOverlay, elements.addTokenForm, true);
    });
}

if (elements.customThemesBtn) {
    elements.customThemesBtn.addEventListener('click', () => {
        console.log('Нажата кнопка Настроить темы');
        toggleModal(elements.customThemesOverlay, elements.customThemesForm, true);
    });
}

if (elements.closeAddToken) {
    elements.closeAddToken.addEventListener('click', () => {
        toggleModal(elements.addTokenOverlay, elements.addTokenForm, false);
    });
}

if (elements.closeCustomThemes) {
    elements.closeCustomThemes.addEventListener('click', () => {
        toggleModal(elements.customThemesOverlay, elements.customThemesForm, false);
    });
}

if (elements.submitToken) {
    elements.submitToken.addEventListener('click', async () => {
        console.log('Нажата кнопка Подтвердить токен');
        const data = {
            gmgn_url: elements.gmgnUrl.value.trim(),
            mexc_name: (elements.mexcName.value.trim() + '_USDT').toUpperCase(),
            display_name: elements.displayName.value.trim(),
            symbolname: elements.symbolName.value.trim()
        };

        if (!data.gmgn_url || !data.mexc_name) {
            alert('Пожалуйста, заполните URL GMGN и Название пары MEXC');
            return;
        }

        try {
            const response = await fetch('/add_token', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            const result = await response.json();
            if (result.success) {
                console.log('Токен успешно добавлен:', result.token);
                const token = result.token;
                if (!clientThresholds[token]) clientThresholds[token] = {};
                clientThresholds[token].view = result.viewThreshold || DEFAULT_THRESHOLDS.view;
                clientThresholds[token].notify = result.notifyThreshold || DEFAULT_THRESHOLDS.notify;
                localStorage.setItem('clientThresholds', JSON.stringify(clientThresholds));
                toggleModal(elements.addTokenOverlay, elements.addTokenForm, false);
                elements.gmgnUrl.value = '';
                elements.mexcName.value = '';
                elements.displayName.value = '';
                elements.symbolName.value = '';
                location.reload();
            } else {
                console.error('Не удалось добавить токен:', result.error);
                alert(`Ошибка: ${result.error}`);
            }
        } catch (error) {
            console.error('Ошибка добавления токена:', error);
            alert('Не удалось добавить токен. Пожалуйста, попробуйте снова.');
        }
    });
}

if (elements.themeToggle) {
    elements.themeToggle.addEventListener('click', () => {
        darkMode = !darkMode;
        localStorage.setItem('darkMode', darkMode);
        updateTheme();
    });
}

if (elements.soundToggle) {
    elements.soundToggle.addEventListener('click', () => {
        soundEnabled = !soundEnabled;
        localStorage.setItem('soundEnabled', soundEnabled);
        updateSoundToggle();
    });
}

function saveTransaction(token, data) {
    let history = JSON.parse(localStorage.getItem(TRANSACTION_HISTORY_KEY) || '{}');
    if (!history[token]) history[token] = [];
    history[token].push(data);
    localStorage.setItem(TRANSACTION_HISTORY_KEY, JSON.stringify(history));
}

function shouldDisplayTransaction(data, token) {
    const threshold = clientThresholds[token]?.view || DEFAULT_THRESHOLDS.view;
    const volumeMatch = data.text.match(/\$([\d\s,.]+)/);
    const volume = volumeMatch ? parseFloat(volumeMatch[1].replace(/[\s,]/g, '')) : 0;
    console.log(`Проверка транзакции для ${token}: volume=${volume}, threshold=${threshold}, isNotification=${data.isNotification}`);
    return volume >= threshold;
}

function addTransactionToDOM(data, container) {
    const div = document.createElement('div');
    div.id = `transaction-${data.id}`;
    div.className = `transaction ${data.isNotification ? 'notification ' : ''}${data.text.includes('buy') ? 'buy' : 'sell'}`;
    const notifyThreshold = clientThresholds[data.token]?.notify || DEFAULT_THRESHOLDS.notify;
    const volumeMatch = data.text.match(/\$([\d\s,.]+)/);
    const volume = volumeMatch ? parseFloat(volumeMatch[1].replace(/[\s,]/g, '')) : 0;
    if (volume >= notifyThreshold) {
        const isBuy = data.text.includes('buy');
        div.style.backgroundColor = isBuy ? 'rgba(76, 175, 80, 0.3)' : 'rgba(244, 67, 54, 0.3)';
        div.style.border = isBuy ? '2px solid rgba(76, 175, 80, 0.7)' : '2px solid rgba(244, 67, 54, 0.7)';
        div.style.animation = 'blink 2s';
        div.style.boxShadow = isBuy ? '0 0 5px rgba(76, 175, 80, 0.5)' : '0 0 5px rgba(244, 67, 54, 0.5)';
        setTimeout(() => {
            div.style.animation = 'none';
        }, 2000);
    }
    div.innerHTML = `${highlightBuySell(data.text)} <span class="price-change" id="price-change-${data.id}">-</span>`;
    container.prepend(div);
    console.log('Транзакция добавлена в DOM:', data.text, 'isNotification:', data.isNotification, 'volume:', volume, 'notifyThreshold:', notifyThreshold);

    if (volume >= notifyThreshold && soundEnabled && elements.notificationSound) {
        const sound = elements.notificationSound;
        sound.play().catch(e => console.error('Ошибка воспроизведения звука:', e));
        if (sound.error) {
            console.error('Ошибка звука:', sound.error);
        }
    }

    // Инициализируем данные об изменении цены для новой транзакции
    if (!transactionPriceChanges[data.id]) {
        transactionPriceChanges[data.id] = {
            token: data.token,
            initialPrice: priceHistory[data.token]?.lastPrice || 0,
            lastUpdate: Date.now()
        };
    }
}

function highlightBuySell(text) {
    return text.replace(/Buy/g, '<span class="text-success"><strong>Покупка</strong></span>')
               .replace(/Sell/g, '<span class="text-error"><strong>Продажа</strong></span>')
               .replace(/notification/, '');
}

function clearTransactions(token) {
    const container = document.getElementById(`transactions-${token}`);
    if (container) {
        container.innerHTML = '';
        console.log(`Транзакции очищены для ${token}`);
    }
    let history = JSON.parse(localStorage.getItem(TRANSACTION_HISTORY_KEY) || '{}');
    delete history[token];
    localStorage.setItem(TRANSACTION_HISTORY_KEY, JSON.stringify(history));
    // Очищаем изменения цен для удаленных транзакций
    transactionPriceChanges = Object.fromEntries(Object.entries(transactionPriceChanges).filter(([_, v]) => v.token !== token));
}

function activateTokens() {
    document.querySelectorAll('.token-card').forEach(card => {
        const token = card.id.replace('card-', '');
        stompClient.send('/app/activate_token', {}, JSON.stringify({ token }));
        console.log(`Активирован токен: ${token}`);
    });
}

function updateTheme() {
    document.body.classList.toggle('dark-mode', darkMode);
    document.body.classList.toggle('light-mode', !darkMode);
    if (elements.themeToggle) {
        elements.themeToggle.innerHTML = darkMode ? '<i class="material-icons-outlined">light_mode</i>' : '<i class="material-icons-outlined">dark_mode</i>';
    }
}

function updateSoundToggle() {
    if (elements.soundToggle) {
        elements.soundToggle.innerHTML = soundEnabled ? '<i class="material-icons-outlined">volume_up</i>' : '<i class="material-icons-outlined">volume_off</i>';
    }
}

function updatePrice(token, type, price, formatPrice) {
    const prevPrice = priceHistory[token]?.[type] || 0;
    priceHistory[token][type] = price;
    const element = document.getElementById(`${type === 'lastPrice' ? 'last-price' : 'mexc-price'}-${token}`);
    if (element) {
        element.textContent = price > 0 ? `$${formatPrice(price)}` : '-';
        if (price > prevPrice) {
            element.classList.add('price-up');
            setTimeout(() => element.classList.remove('price-up'), 1000);
        }
    }
    lastPrices[token] = { ...lastPrices[token], [type]: price };
}

function updatePriceDiff(token) {
    const gmgnPrice = priceHistory[token]?.lastPrice || 0;
    const mexcPrice = priceHistory[token]?.mexcPrice || 0;
    const spreadElement = document.getElementById(`spread-${token}`);
    if (spreadElement && gmgnPrice > 0 && mexcPrice > 0) {
        const spreadPercent = ((mexcPrice - gmgnPrice) / gmgnPrice) * 100;
        spreadElement.textContent = `${spreadPercent >= 0 ? '+' : ''}${spreadPercent.toFixed(1)}%`;
        spreadElement.className = 'spread-value ' + (spreadPercent >= 0 ? 'text-success' : 'text-error');
    }
}

function updateTransactionPriceChanges(token) {
    const currentPrice = priceHistory[token]?.lastPrice || 0;
    Object.values(transactionPriceChanges).forEach(change => {
        if (change.token === token) {
            const timeDiff = (Date.now() - change.lastUpdate) / 1000; // В секундах
            if (timeDiff >= 20) {
                const initialPrice = change.initialPrice;
                const priceChangePercent = ((currentPrice - initialPrice) / initialPrice) * 100;
                updateTransactionPriceChangeDisplay(change.id, priceChangePercent);
                change.lastUpdate = Date.now(); // Обновляем время последнего обновления
            }
        }
    });
}

function updateTransactionPriceChangeDisplay(transactionId, priceChangePercent = null) {
    const element = document.getElementById(`price-change-${transactionId}`);
    if (element) {
        const change = transactionPriceChanges[transactionId];
        if (!priceChangePercent && change) {
            const currentPrice = priceHistory[change.token]?.lastPrice || 0;
            const timeDiff = (Date.now() - change.lastUpdate) / 1000;
            if (timeDiff >= 20) {
                priceChangePercent = ((currentPrice - change.initialPrice) / change.initialPrice) * 100;
                change.lastUpdate = Date.now();
            } else {
                priceChangePercent = ((currentPrice - change.initialPrice) / change.initialPrice) * 100;
            }
        }
        if (priceChangePercent !== null) {
            const arrow = priceChangePercent >= 0 ? '↑' : '↓';
            element.textContent = `${arrow}${Math.abs(priceChangePercent).toFixed(2)}%`;
            element.className = `price-change ${priceChangePercent >= 0 ? 'text-success' : 'text-error'}`;
        }
    }
}

function startPriceChangeUpdates() {
    setInterval(() => {
        Object.keys(priceHistory).forEach(token => {
            updateTransactionPriceChanges(token);
        });
    }, PRICE_UPDATE_INTERVAL);
}

// Добавляем CSS-анимацию для мигания
const style = document.createElement('style');
style.innerHTML = `
    @keyframes blink {
        0% { opacity: 1; }
        50% { opacity: 0.5; }
        100% { opacity: 1; }
    }
    .price-change {
        margin-left: 5px;
        font-size: 0.9em;
    }
`;
document.head.appendChild(style);

// Initialize
updateTheme();
updateSoundToggle();