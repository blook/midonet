/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman

import akka.actor.{ActorSystem, ActorRef, ActorContext}


/**
 * Copyright 2012 Midokura Europe SARL
 * User: Rossella Sblendido <rossella@midokura.com>
 * Date: 8/30/12
 */

trait Referenceable {

    def getRef()(implicit context: ActorContext): ActorRef = {
        context.actorFor(path)
    }

    def getRef(system: ActorSystem): ActorRef = {
        system.actorFor(path)
    }

    def Name: String

    protected def Prefix: String = "/user/%s" format supervisorName

    protected def path: String = "%s/%s".format(Prefix, Name)

    protected def supervisorName = "midolman"

}
