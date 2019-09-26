#!/bin/sh -l

set -ue

cd /usr/src/app
clj -m tvanhens.clj-kondo-action.main $1
