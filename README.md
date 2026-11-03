# **Local Meme-Finder**

### *Find the picture you need in your gallery by meaning or by the text on it. Everything runs on your phone, no internet required*

### *Находит нужную картинку в галерее по смыслу и по тексту на ней. Всё работает на телефоне, без интернета*


Thousands of memes in your gallery and no way to find the one you want? Meme Finder goes through your pictures on its own and remembers what is shown on them and what is written on them. After that, just type “hamster”, “kitten on a laptop”, or a piece of a caption, and the picture turns up

Тысячи мемов в галерее, а нужный не найти? Мем-Искатель сам просматривает ваши картинки и запоминает, что на них изображено и что написано. Потом достаточно ввести «хомяк», «котёнок на ноутбуке» или кусок фразы с картинки, и она найдётся

Features

* -Search by meaning. Understands queries in Russian, such as “dog”, “food”, “flag” or “city”. The picture doesn’t need a caption

* -Search by text. Reads the captions on memes and screenshots. It still finds them when the words on the picture run together, the word ending is different, or letters were recognized as Latin look-alikes

* -Meaning tags. When you open a picture, you can see what the app “saw” in it

* -Handy actions. Share to any app, open in your gallery, hide a picture from search

* -Folder selection. You can see how many pictures in each folder have been processed. Indexing runs in the background, and anything already processed is not redone

* -Light and dark themes

Что умеет

* -Поиск по смыслу. Понимает запросы на русском: «собака», «еда», «флаг», «город». Картинке не нужна подпись

* -Поиск по тексту. Читает надписи на мемах и скриншотах. Находит, даже если слова на картинке слиплись, окончание другое или буквы распознались латиницей

* -Теги смысла. В просмотре картинки видно, что приложение в ней «увидело»

* -Удобные действия. Поделиться в любое приложение, открыть в галерее, скрыть картинку из поиска

* -Выбор папок. Видно, сколько картинок в каждой папке уже обработано

* -Индексация идёт в фоне, обработанное не пересчитывается

* -Светлая и тёмная тема

Privacy
All pictures are processed on the phone itself. The app has no internet permission, so it technically cannot send your photos anywhere. The index is stored only in the app’s private storage

Приватность
Все картинки обрабатываются на самом телефоне. У приложения нет разрешения на доступ в интернет, поэтому отправить фото наружу оно технически не может. Индекс хранится только в памяти приложения

Good to know
* -Android 10 or newer, 64-bit processor
* -The search models (about 470 MB) are copied to the phone once and selected on first launch
* -The first indexing of a large gallery takes a few hours, and it runs slower in the background than with the app open. If you need it faster, there is a “Meaning only” mode
* -This is a build for personal use, not from an app store: it is installed manually

Что нужно знать
* -Android 10 и новее, 64-разрядный процессор
* -Модели поиска (около 470 МБ) копируются на телефон один раз и выбираются при первом запуске
* -Первая индексация большой галереи занимает несколько часов, в фоне идёт медленнее, чем при открытом приложении. Если нужно быстрее, есть режим «Только смысл»
* -Это сборка для личного пользования, не из магазина приложений: установка вручную

## HOW TO USE

### 1. Install the app
1. Open the [Releases page](https://github.com/TereBebr/meme-finder/releases/latest) on your phone and download `meme-finder-build-N.apk` (the newest build).
2. Open the downloaded file. Android will ask to allow installing apps from this source: allow it for your browser or file manager.
3. If Play Protect warns about an unknown app, choose **Install anyway** (this is a personal build, it is not distributed through an app store).
4. New versions install on top of the old one and keep your index.

### 2. Get the model files (one time, about 470 MB)
Download all four files from the [`models-v1` release](https://github.com/TereBebr/meme-finder/releases/tag/models-v1) and put them together in one folder on the phone, for example `Download/meme-models`:

* `ch_PP-OCRv5_det_mobile.onnx`
* `eslav_PP-OCRv5_rec_mobile.onnx`
* `visual_q8.onnx`
* `text_q8.onnx`

If the files are on a computer, copy the folder to the phone over USB.

### 3. First launch
1. Allow access to photos (choose **Allow all**, otherwise only the pictures you pick will be visible to the app) and notifications.
2. Tap **Choose models folder** and select the folder from step 2. The files are copied into the app, afterwards you can delete the originals.

### 4. Index the gallery
1. Tap **Индекс** (Index), choose what to extract: **meaning + text**, **meaning only** (much faster) or **text only**, and tick the folders to process. For each folder you can see how many pictures are already done.
2. Tap **Start**. Indexing runs in the background, you can close the app, progress is shown in the notification. For a first try, pick one small folder to see how fast your phone is.
3. If it is much slower with the screen off: open **⚙ → Open battery settings** and set Meme Finder to **Don't optimize**.

### 5. Search
* Type in the search box, for example `хомяк`, `котёнок на ноутбуке`, or a phrase from a caption. The chips switch between **All**, **Text** and **Meaning**.
* Tap a result to open it: you will see the meaning tags and the recognized text, and can **Share**, **Open in gallery** or **Hide from search**.
* Long-press to select several pictures and share or hide them together.
* **⚙** has the theme, hidden pictures, battery settings, model replacement and a **Check models** self-test.

### Troubleshooting
* **"Meaning" finds nothing:** open ⚙ → **Check models**. Every line should have a check mark. A red line shows which step fails.
* **App not installed:** an older build signed with a different key is installed; uninstall it first (the index will be lost).
* **Not all pictures are indexed:** check that photo access is set to **Allow all** in the Android app settings.

---

## КАК ИСПОЛЬЗОВАТЬ

### 1. Установите приложение
1. Откройте [страницу релизов](https://github.com/TereBebr/meme-finder/releases/latest) на телефоне и скачайте `meme-finder-build-N.apk` (самая новая сборка).
2. Откройте скачанный файл. Android попросит разрешить установку из этого источника: разрешите для браузера или файлового менеджера.
3. Если Play Protect предупредит о неизвестном приложении, выберите **Всё равно установить** (это личная сборка, в магазинах приложений её нет).
4. Новые версии ставятся поверх старой, индекс сохраняется.

### 2. Скачайте файлы моделей (один раз, около 470 МБ)
Скачайте все четыре файла из [релиза `models-v1`](https://github.com/TereBebr/meme-finder/releases/tag/models-v1) и сложите вместе в одну папку на телефоне, например `Download/meme-models`:

* `ch_PP-OCRv5_det_mobile.onnx`
* `eslav_PP-OCRv5_rec_mobile.onnx`
* `visual_q8.onnx`
* `text_q8.onnx`

Если файлы на компьютере, скопируйте папку на телефон по USB.

### 3. Первый запуск
1. Разрешите доступ к фото (выберите **Разрешить все**, иначе приложение увидит только выбранные вручную картинки) и уведомления.
2. Нажмите **Выбрать папку с моделями** и укажите папку из шага 2. Файлы копируются внутрь приложения, исходные потом можно удалить.

### 4. Проиндексируйте галерею
1. Нажмите **Индекс**, выберите, что извлекать: **смысл + текст**, **только смысл** (заметно быстрее) или **только текст**, и отметьте папки. У каждой папки видно, сколько картинок уже обработано.
2. Нажмите **Начать индексацию**. Она идёт в фоне, приложение можно закрыть, прогресс виден в уведомлении. Для первой пробы выберите одну небольшую папку, чтобы понять скорость вашего телефона.
3. Если с погашенным экраном заметно медленнее: **⚙ → Открыть настройки батареи** и поставьте для «Мем-Искатель» **Не оптимизировать**.

### 5. Поиск
* Введите запрос, например `хомяк`, `котёнок на ноутбуке` или фразу с картинки. Переключатель **Всё / Текст / Смысл** выбирает, как искать.
* Нажмите на результат: видны теги смысла и распознанный текст, можно **Поделиться**, **Открыть в галерее** или **Скрыть из поиска**.
* Долгое нажатие выбирает несколько картинок, их можно вместе отправить или скрыть.
* В **⚙** находятся тема, скрытые картинки, настройки батареи, замена моделей и самопроверка **Проверить модели**.

### Если что-то не работает
* **«Смысл» ничего не находит:** откройте ⚙ → **Проверить модели**. У каждой строки должна быть галочка, красная строка показывает, на каком шаге сбой.
* **Приложение не установилось:** стоит старая сборка, подписанная другим ключом. Удалите её и поставьте заново (индекс при этом пропадёт).
* **Проиндексированы не все картинки:** в настройках Android для приложения проверьте, что доступ к фото стоит **Разрешить все**.

---

## Models / Модели

The app runs four ONNX models on the phone. / Приложение запускает на телефоне четыре ONNX-модели.

| File | What it is | Original |
|---|---|---|
| `ch_PP-OCRv5_det_mobile.onnx` | PP-OCRv5 text detection / поиск текста на картинке | [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) (Baidu), ONNX by [RapidOCR](https://github.com/RapidAI/RapidOCR) |
| `eslav_PP-OCRv5_rec_mobile.onnx` | PP-OCRv5 East-Slavic text recognition / чтение текста (русский, белорусский, украинский, английский) | PaddleOCR (Baidu), ONNX by RapidOCR |
| `visual_q8.onnx` | CLIP ViT-B/32 image encoder, int8 / картинки → смысл | [OpenAI CLIP](https://huggingface.co/openai/clip-vit-base-patch32) via the [Immich ONNX export](https://huggingface.co/immich-app/XLM-Roberta-Large-Vit-B-32) |
| `text_q8.onnx` | M-CLIP XLM-RoBERTa-Large multilingual text encoder, vocabulary trimmed to Cyrillic + Latin, int8 / слова → смысл | [M-CLIP](https://huggingface.co/M-CLIP/XLM-Roberta-Large-Vit-B-32) via the Immich ONNX export |

The two CLIP files were converted from the originals (trimmed vocabulary, int8 quantization) to fit on a phone. All credit and licenses belong to the original authors. / Два CLIP-файла сконвертированы из оригиналов (урезанный словарь, int8), чтобы поместиться на телефон. Все права и лицензии принадлежат авторам оригиналов.
