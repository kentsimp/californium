#!/bin/sh

#/*******************************************************************************
# * Copyright (c) 2020 Bosch.IO GmbH and others.
# * 
# * All rights reserved. This program and the accompanying materials
# * are made available under the terms of the Eclipse Public License v2.0
# * and Eclipse Distribution License v1.0 which accompany this distribution.
# * 
# * The Eclipse Public License is available at
# *    http://www.eclipse.org/legal/epl-v20.html
# * and the Eclipse Distribution License is available at
# *    http://www.eclipse.org/org/documents/edl-v10.html.
# * 
# * Contributors:
# *    Achim Kraus (Bosch.IO GmbH) - initial script
# ******************************************************************************/

echo "deploy cf-extserver to microk8s using namespace cali"

CONTAINER=cf-extserver-jdk11-slim:2.5.0

# local container registry of microk8s
REGISTRY=localhost:32000

if [ ! -d "target" ] ; then
   if [ -d "../target" ] ; then
      cd ..
   fi
fi

# build container
docker build . -t ${REGISTRY}/${CONTAINER} -f service/Dockerfile

# push to container registry
docker push ${REGISTRY}/${CONTAINER}

# update from container registry
microk8s.ctr i pull ${REGISTRY}/${CONTAINER}

# namespace
microk8s.kubectl delete namespace cali
microk8s.kubectl create namespace cali

token=$(microk8s kubectl -n kube-system get secret | grep default-token | cut -d " " -f1)
token=$(microk8s kubectl -n kube-system describe secret $token | grep token: )
token=$(echo $token | cut -d " " -f2 )

echo $token

microk8s.kubectl -n cali create secret generic cf-extserver-config \
  --from-literal=kubectl_token="$token" \
  --from-literal=kubectl_host="https://10.152.183.1" \
  --from-literal=kubectl_namespace="cali" \
  --from-literal=kubectl_selector="app%3Dcf-extserver"

# deploy
microk8s.kubectl -n cali apply -f service/k8s.yaml
