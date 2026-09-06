# Image du cerveau Pepper. Construite pour amd64 et arm64 : mini-PC, Mac, Raspberry Pi.
FROM python:3.12-slim

# ffmpeg : décodage audio pour faster-whisper, et ffprobe pour valider les vidéos
# déposées dans la médiathèque.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /srv

# Les dépendances d'abord : elles changent bien moins souvent que le code, et cette
# couche est la plus longue à construire.
COPY requirements.txt /srv/brain/requirements.txt
RUN pip install --no-cache-dir -r /srv/brain/requirements.txt

COPY . /srv/brain

ENV PYTHONPATH=/srv \
    PYTHONUNBUFFERED=1 \
    BRAIN_DATA=/data \
    BRAIN_MODELS=/models \
    BRAIN_HOST=0.0.0.0 \
    BRAIN_PORT=8770

VOLUME ["/data", "/models"]
EXPOSE 8770

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
    CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8770/api/health')"

CMD ["python", "-m", "brain.main"]
